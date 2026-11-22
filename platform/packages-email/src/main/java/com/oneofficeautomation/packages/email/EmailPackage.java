package com.oneofficeautomation.packages.email;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import jakarta.mail.search.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Email over standard protocols: SMTP to send, IMAP to read. Works with Microsoft 365, Gmail, Exchange (IMAP enabled)
 * and any mail server. Sign-in with a password or an OAuth 2.0 access token (XOAUTH2), both taken from the vault.
 */
@OoaPackage(id = "ooa.email", name = "Email", version = "1.0.0",
        description = "Send emails with attachments, read and search mailboxes, save attachments, move, mark and delete emails.")
public final class EmailPackage {

    static final class MailSession implements AutoCloseable {
        final Store store;
        final boolean pop3;
        final Map<String, Folder> open = new HashMap<>();
        /** Outgoing server kept with the mailbox, so send, reply and forward can leave the SMTP fields empty. */
        Smtp smtp;
        MailSession(Store s) { this(s, false); }
        MailSession(Store s, boolean pop3) { store = s; this.pop3 = pop3; }
        Folder folder(String name) throws MessagingException {
            Folder f = open.get(name);
            if (f instanceof org.eclipse.angus.mail.imap.IMAPFolder imap && f.isOpen()) {
                // an open folder only learns about emails moved or delivered into it since it was opened on the next
                // server round trip; without it, later steps in the run would work on a stale list
                imap.doCommand(p -> { p.simpleCommand("NOOP", null); return null; });
            }
            if (f == null || !f.isOpen()) {
                f = store.getFolder(name);
                if (!f.exists()) throw new ActionException("EMAIL.NO_FOLDER", "Mail folder '" + name + "' not found", "Use Get folders to see the exact names.");
                f.open(Folder.READ_WRITE);
                open.put(name, f);
            }
            return f;
        }
        @Override public void close() throws MessagingException {
            for (Folder f : open.values()) if (f.isOpen()) f.close(true);
            store.close();
        }
    }

    /** Where and how to send: the step's own SMTP fields, or the outgoing server kept by Connect. */
    record Smtp(String host, int port, String security, String user, Object password, Object token) {
        /** Without a password or token the server is used without signing in (an internal relay). */
        boolean signIn() { return password != null || token != null; }

        Session session() { return session(60, false); }

        /** The session with a send time limit; partial = deliver to the good addresses when the server refuses some. */
        Session session(int timeoutSeconds, boolean partial) {
            Properties p = props("smtp", host, port, security, token != null);
            int t = Math.max(1, timeoutSeconds) * 1000;
            p.put("mail.smtp.timeout", String.valueOf(t));
            p.put("mail.smtp.writetimeout", String.valueOf(t));
            if (partial) p.put("mail.smtp.sendpartial", "true");
            if (!signIn()) p.put("mail.smtp.auth", "false");
            return Session.getInstance(p);
        }

        void connect(Transport t) throws MessagingException {
            if (signIn()) t.connect(host, port, user, secret(password, token)); else t.connect(host, port, null, null);
        }
    }

    static Smtp smtp(ActionContext ctx, String session, String host, int port, String security, String user, Object password, Object token) {
        if (host != null && !host.isBlank()) {
            if ((password != null || token != null) && (user == null || user.isBlank()))
                throw new ActionException("EMAIL.BAD_INPUT", "Give the user that signs in to " + host, "Fill in user.");
            return new Smtp(host, port, security, user, password, token);
        }
        Object o = session == null ? null : ctx.sessions().get(session);
        if (o instanceof MailSession m && m.smtp != null) {
            Smtp k = m.smtp;
            boolean own = password != null || token != null;
            return new Smtp(k.host(), k.port(), k.security(), user == null || user.isBlank() ? k.user() : user,
                    own ? password : k.password(), own ? token : k.token());
        }
        throw new ActionException("EMAIL.NO_SMTP", "No outgoing (SMTP) server for this step",
                "Fill in smtp_host and user, or give Connect an smtp_host so the mailbox session can send.");
    }

    private static ActionException mail(MessagingException e) {
        String m = String.valueOf(e.getMessage());
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String why = root instanceof java.net.ConnectException ? "the connection was refused (is the server running and the port open?)"
                : root instanceof java.net.UnknownHostException ? "the host name is unknown"
                : root instanceof java.net.SocketTimeoutException ? "the server did not answer in time"
                : root instanceof javax.net.ssl.SSLException ? "the secure connection failed (check security: ssl for port 465, starttls for 587)" : null;
        if (e instanceof AuthenticationFailedException) return new ActionException("EMAIL.AUTH_FAILED", "Mail sign-in failed: " + m,
                "Check the user and password or token; Microsoft 365 and Gmail need OAuth or an app password.");
        if (e instanceof SendFailedException sf && sf.getInvalidAddresses() != null && sf.getInvalidAddresses().length > 0)
            return new ActionException("EMAIL.BAD_ADDRESS", "The server refused the address(es) " + Arrays.toString(sf.getInvalidAddresses()) + ": " + m, "Check the recipient addresses.");
        if (why != null || m.contains("connect") || m.contains("timed out") || m.contains("UnknownHost"))
            return new ActionException("EMAIL.UNREACHABLE", "Cannot reach the mail server: " + (why != null ? why + " (" + m.replaceAll(";\\s*timeout -?\\d+", "") + ")" : m),
                    "Check host, port, security and network access.", true);
        return new ActionException("EMAIL.ERROR", m, "Check the mail server's address, port and the sign-in in the connection; the message has the server's answer.");
    }

    private static Properties props(String protocol, String host, int port, String security, boolean oauth) {
        return props(protocol, host, port, security, oauth, 15);
    }

    static Properties props(String protocol, String host, int port, String security, boolean oauth, int connectSeconds) {
        Properties p = new Properties();
        String pre = "mail." + protocol + ".";
        int c = Math.max(1, connectSeconds);
        p.put(pre + "host", host);
        p.put(pre + "port", String.valueOf(port));
        p.put(pre + "connectiontimeout", String.valueOf(c * 1000));
        p.put(pre + "timeout", String.valueOf(Math.max(60, c) * 1000));
        p.put(pre + "writetimeout", String.valueOf(Math.max(60, c) * 1000));
        if ("ssl".equals(security)) { p.put(pre + "ssl.enable", "true"); p.put(pre + "ssl.checkserveridentity", "true"); }
        if ("starttls".equals(security)) { p.put(pre + "starttls.enable", "true"); p.put(pre + "starttls.required", "true"); p.put(pre + "ssl.checkserveridentity", "true"); }
        if (oauth) { p.put(pre + "auth.mechanisms", "XOAUTH2"); p.put(pre + "auth.login.disable", "true"); p.put(pre + "auth.plain.disable", "true"); }
        if (protocol.startsWith("smtp")) p.put(pre + "auth", "true");
        // read bodies and attachments without the server marking the email read (C1): a step sets the read flag
        // itself, and only when asked (mark_read)
        if (protocol.startsWith("imap")) p.put(pre + "peek", "true");
        return p;
    }

    private static String secret(Object password, Object token) {
        if (token instanceof SecureString t) return t.reveal();
        if (password instanceof SecureString p) return p.reveal();
        throw new ActionException("EMAIL.NO_CREDENTIAL", "Give a password or an OAuth access token from the vault", "Fill in password or access_token.");
    }

    private static InternetAddress[] addresses(Object v) throws AddressException {
        if (v == null) return new InternetAddress[0];
        List<String> parts = new ArrayList<>();
        if (v instanceof List<?> l) for (Object o : l) parts.add(Values.text(o)); else parts.add(Values.text(v));
        return InternetAddress.parse(String.join(",", parts).replace(';', ','), true);
    }

    @Action(id = "send", name = "Send email", category = "Send", timeoutSeconds = 120, capabilities = "network",
            description = "Send an email (plain text or HTML) with optional CC, BCC and attachments through an SMTP server.",
            panel = "to, cc, bcc, reply_to, importance, subject, attachments, body_type, body, html, [Send through a specific server: smtp_host, port, security, user, password, access_token] | from, footer, read_receipt, inline_images, skip_missing_attachments, skip_invalid_addresses, charset, timeout_seconds, session")
    @Output(name = "message_id", type = OoaType.STRING, description = "Message-ID of the sent email.")
    public Map<String, Object> send(ActionContext ctx,
            @Input(name = "smtp_host", type = OoaType.STRING, required = false, example = "smtp.office365.com", description = D_SMTP) String host,
            @Input(name = "port", type = OoaType.NUMBER, required = false, defaultValue = "587", description = "587 for STARTTLS, 465 for SSL.") int port,
            @Input(name = "security", type = OoaType.STRING, required = false, defaultValue = "\"starttls\"", enumValues = {"starttls", "ssl", "none"}, description = "Connection security.") String security,
            @Input(name = "user", type = OoaType.STRING, required = false, description = "Mailbox user name; the mailbox session's user when empty.") String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false,
                    description = "Password or app password from the vault; with neither password nor token the server is used without signing in (internal relay).") Object password,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = D_TOKEN) Object token,
            @Input(name = "from", type = OoaType.STRING, required = false, description = "Sender address; the user when empty.") String from,
            @Input(name = "to", description = "Recipient address(es): text separated by , or ; or a List.") Object to,
            @Input(name = "cc", required = false, description = "CC address(es).") Object cc,
            @Input(name = "bcc", required = false, description = "BCC address(es).") Object bcc,
            @Input(name = "subject", type = OoaType.STRING, description = "Subject.") String subject,
            @Input(name = "body", type = OoaType.STRING, required = false, defaultValue = "\"\"", description = "Message body.") String body,
            @Input(name = "html", type = OoaType.BOOLEAN, required = false, defaultValue = "false", visibleWhen = "html=true", description = D_HTML) boolean html,
            @Input(name = "attachments", type = OoaType.LIST, required = false, description = "File paths to attach.") List<Object> attachments,
            @Input(name = "importance", type = OoaType.STRING, required = false, defaultValue = "\"normal\"", enumValues = {"normal", "high", "low"},
                    description = "Importance shown by the recipient's mail program.") String importance,
            @Input(name = "reply_to", required = false, description = "Address(es) that replies go to, when not the sender.") Object replyTo,
            @Input(name = "read_receipt", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Ask the recipient's mail program to confirm when the email is opened.") boolean receipt,
            @Input(name = "inline_images", type = OoaType.LIST, required = false,
                    description = "HTML body only: image files embedded in the email; refer to each in the body as cid: plus its file name, e.g. <img src=\"cid:logo.png\">.") List<Object> inline,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"",
                    description = "Mailbox session whose outgoing server is used when smtp_host is empty.") String mailSession,
            @Input(name = "body_type", type = OoaType.STRING, required = false, defaultValue = "\"plain\"", enumValues = {"plain", "html", "rich"}, description = D_BODY_TYPE) String bodyType,
            @Input(name = "footer", type = OoaType.STRING, required = false, description = D_FOOTER) String footer,
            @Input(name = "skip_missing_attachments", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = D_SKIP_MISSING) boolean skipMissing,
            @Input(name = "skip_invalid_addresses", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = D_SKIP_INVALID) boolean skipInvalid,
            @Input(name = "charset", type = OoaType.STRING, required = false, defaultValue = "\"UTF-8\"", example = "ISO-8859-1", description = D_CHARSET) String charset,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "60", newStepDefault = "120", min = 1, max = 600, description = D_SEND_TIMEOUT) int timeout) {
        Smtp k = smtp(ctx, mailSession, host, port, security, user, password, token);
        Session session = k.session(timeout, skipInvalid);
        boolean asHtml = isHtml(bodyType, html);
        String cs = charset(charset);
        try {
            Object t = skipInvalid ? validOnly(ctx, to, "to") : to, c = skipInvalid ? validOnly(ctx, cc, "cc") : cc, b = skipInvalid ? validOnly(ctx, bcc, "bcc") : bcc;
            MimeMessage msg = buildMessage(session, from != null && !from.isBlank() ? from : k.user(), t, c, b, subject, withFooter(body, footer, asHtml), asHtml,
                    fileParts(ctx, attachments, skipMissing), inlineParts(ctx, inline), importance, replyTo, receipt, cs);
            transmit(ctx, msg, k, session, skipInvalid);
            ctx.info("Sent '" + subject + "' to " + Arrays.toString(msg.getRecipients(Message.RecipientType.TO)));
            return Map.of("message_id", Objects.toString(msg.getMessageID(), ""));
        } catch (AddressException e) {
            throw new ActionException("EMAIL.BAD_ADDRESS", "Invalid email address: " + e.getMessage(), "Check the sender and recipient addresses, or tick skip_invalid_addresses.");
        } catch (MessagingException e) { throw mail(e);
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), "Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it.", true); }
    }

    static final String D_TOKEN = "OAuth 2.0 access token (Microsoft 365, Gmail) from the vault; or leave it empty and choose a saved sign-in connection, which gets and renews the token.";
    static final String D_HTML = "Older steps: the body is HTML. New steps choose the body type instead.";
    static final String D_BODY_TYPE = "How the body is written: plain text, HTML code, or rich text from the editor (sent as HTML).";
    static final String D_FOOTER = "Text added after the body, such as a signature or a disclaimer (written like the body).";
    static final String D_SKIP_MISSING = "Send without an attachment whose file is missing (a warning says which) instead of failing.";
    static final String D_SKIP_INVALID = "Leave out addresses that are not valid or that the server refuses (a warning says which) and send to the others.";
    static final String D_CHARSET = "Character set of the subject and body, e.g. UTF-8 (the default) or ISO-8859-1.";
    static final String D_SEND_TIMEOUT = "How long to wait for the mail server while sending, in seconds.";

    /** The body is HTML when the body type says so (html or rich text), or, on older steps, when the html tick is on. */
    static boolean isHtml(String bodyType, boolean html) {
        String t = bodyType == null || bodyType.isBlank() ? "plain" : bodyType.strip().toLowerCase(Locale.ROOT);
        return switch (t) {
            case "plain", "text" -> html;
            case "html", "rich" -> true;
            default -> throw new ActionException("EMAIL.BAD_INPUT", "Unknown body type '" + bodyType + "'", "Use plain, html or rich.");
        };
    }

    /** The body with the footer after it (an empty line between), or the body alone. */
    static String withFooter(String body, String footer, boolean html) {
        String b = body == null ? "" : body;
        if (footer == null || footer.isBlank()) return b;
        return b.isEmpty() ? footer : b + (html ? "<br><br>" : "\r\n\r\n") + footer;
    }

    /** A supported character set name; UTF-8 when empty. */
    static String charset(String name) {
        String n = name == null || name.isBlank() ? "UTF-8" : name.strip();
        try { if (java.nio.charset.Charset.isSupported(n)) return n; } catch (java.nio.charset.IllegalCharsetNameException ignored) { }
        throw new ActionException("EMAIL.BAD_INPUT", "Unknown character set '" + name + "'", "Use UTF-8, ISO-8859-1, windows-1252 or another standard name.");
    }

    /** The valid addresses of a recipient field; the invalid ones are left out with a warning. */
    static List<Object> validOnly(ActionContext ctx, Object v, String input) {
        if (v == null) return null;
        List<String> parts = new ArrayList<>();
        if (v instanceof List<?> l) for (Object o : l) parts.add(Values.text(o)); else parts.add(Values.text(v));
        String all = String.join(",", parts).replace(';', ',');
        List<Object> good = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        try {
            for (InternetAddress a : InternetAddress.parse(all, true)) {
                try { a.validate(); good.add(a.toString()); } catch (AddressException e) { bad.add(a.toString()); }
            }
        } catch (AddressException whole) {
            for (String one : all.split(",")) {
                if (one.isBlank()) continue;
                try { InternetAddress a = new InternetAddress(one.strip(), true); a.validate(); good.add(a.toString()); }
                catch (AddressException e) { bad.add(one.strip()); }
            }
        }
        if (!bad.isEmpty() && ctx != null) ctx.warn("Left out invalid address(es) in " + input + ": " + String.join(", ", bad));
        return good;
    }

    /** Sends; with partial on, addresses the server refuses are left out with a warning when the others got it. */
    static void transmit(ActionContext ctx, MimeMessage msg, Smtp k, Session session, boolean partial) throws MessagingException {
        try (Transport t = session.getTransport("smtp")) {
            k.connect(t);
            t.sendMessage(msg, msg.getAllRecipients());
        } catch (SendFailedException e) {
            Address[] sent = e.getValidSentAddresses();
            if (!partial || sent == null || sent.length == 0) throw e;
            if (ctx != null) ctx.warn("The server refused " + Arrays.toString(e.getInvalidAddresses()) + "; sent to " + Arrays.toString(sent));
        }
    }

    /** Jakarta Mail store protocol for the reading protocol and connection security, e.g. imaps or pop3. */
    static String storeProtocol(String protocol, String security) {
        String p = protocol == null || protocol.isBlank() ? "imap" : protocol.trim().toLowerCase(Locale.ROOT);
        if (!p.equals("imap") && !p.equals("pop3")) throw new ActionException("EMAIL.BAD_INPUT", "Unknown protocol '" + protocol + "'", "Use imap or pop3.");
        return "ssl".equals(security == null || security.isBlank() ? "ssl" : security.trim().toLowerCase(Locale.ROOT)) ? p + "s" : p;
    }

    /** The port to use: the given one, except that the IMAP SSL default (993) is swapped for the usual port of the chosen protocol and security. */
    static int readPort(String storeProtocol, String security, int port) {
        if (port != 993) return port;
        return switch (storeProtocol) { case "imap" -> 143; case "pop3s" -> 995; case "pop3" -> 110; default -> 993; };
    }

    /** Sign-in name. Exchange and Microsoft 365 open a shared mailbox as user\shared with a password, or as the shared address with an OAuth token. */
    static String loginName(String user, String shared, boolean oauth) {
        if (shared == null || shared.isBlank()) return user;
        return oauth ? shared.trim() : user + "\\" + shared.trim();
    }

    @Action(id = "connect", name = "Connect to mailbox", category = "Mailbox", capabilities = "network",
            description = "Sign in to a mailbox over IMAP or POP3 as a named session, optionally opening a shared mailbox.",
            panel = "imap_host, user, (Sign in with: A password = password / An access token = access_token), protocol, security, port, [Keep an outgoing server for sending: smtp_host, smtp_port, smtp_security] | shared_mailbox, timeout_seconds, session")
    public Map<String, Object> connect(ActionContext ctx,
            @Input(name = "imap_host", type = OoaType.STRING, example = "outlook.office365.com", description = "Mail server that the agent reads from (IMAP or POP3 host).") String host,
            @Input(name = "port", type = OoaType.NUMBER, required = false, defaultValue = "993", description = "Server port: 993 IMAP over SSL, 143 IMAP, 995 POP3 over SSL, 110 POP3. Left at 993, the usual port of the chosen protocol and security is used.") int port,
            @Input(name = "user", type = OoaType.STRING, description = "Mailbox user name.") String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password or app password from the vault.") Object password,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = D_TOKEN) Object token,
            @Input(name = "protocol", type = OoaType.STRING, required = false, defaultValue = "\"imap\"", enumValues = {"imap", "pop3"},
                    description = "imap keeps folders and read status on the server; pop3 reads only the inbox and cannot mark or move emails.") String protocol,
            @Input(name = "security", type = OoaType.STRING, required = false, defaultValue = "\"ssl\"", enumValues = {"ssl", "starttls", "none"}, description = "Connection security.") String security,
            @Input(name = "shared_mailbox", type = OoaType.STRING, required = false, example = "invoices@acme.com",
                    description = "Open this shared mailbox with the user's own sign-in (Exchange and Microsoft 365; the user needs full access to it).") String shared,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "15", newStepDefault = "60", min = 1, max = 300, description = "How long to wait for the server to answer when connecting.") int timeout,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"", description = "Session name.") String session,
            @Input(name = "smtp_host", type = OoaType.STRING, required = false, example = "smtp.office365.com",
                    description = "Outgoing server to keep with this session, so Send, Reply and Forward need no server or sign-in of their own.") String smtpHost,
            @Input(name = "smtp_port", type = OoaType.NUMBER, required = false, defaultValue = "587", visibleWhen = "smtp_host!=", min = 1, max = 65535,
                    description = "587 for STARTTLS, 465 for SSL.") int smtpPort,
            @Input(name = "smtp_security", type = OoaType.STRING, required = false, defaultValue = "\"starttls\"", enumValues = {"starttls", "ssl", "none"}, visibleWhen = "smtp_host!=",
                    description = "Outgoing connection security.") String smtpSecurity) {
        String proto = storeProtocol(protocol, security);
        int p = readPort(proto, security, port);
        String sec = security == null || security.isBlank() ? "ssl" : security.trim().toLowerCase(Locale.ROOT);
        try {
            Session s = Session.getInstance(props(proto, host, p, sec, token != null, timeout));
            Store store = s.getStore(proto);
            store.connect(host, p, loginName(user, shared, token != null), secret(password, token));
            MailSession m = new MailSession(store, proto.startsWith("pop3"));
            if (smtpHost != null && !smtpHost.isBlank())
                m.smtp = new Smtp(smtpHost.strip(), smtpPort, smtpSecurity == null || smtpSecurity.isBlank() ? "starttls" : smtpSecurity, user, password, token);
            ctx.sessions().put(session, m);
        } catch (MessagingException e) { throw mail(e); }
        return Map.of();
    }

    private static MailSession ms(ActionContext ctx, String name) { return ctx.session(name, MailSession.class); }

    private static void imapOnly(MailSession s, String what) {
        if (s.pop3) throw new ActionException("EMAIL.NOT_SUPPORTED", what + " needs an IMAP mailbox; POP3 has no folders or read status on the server",
                "Connect with protocol imap.");
    }

    /** Message by its id: the IMAP UID, or the message number for POP3, which has no stable numeric ids. */
    static Message byUid(Folder f, long uid) throws MessagingException {
        Message m;
        if (f instanceof UIDFolder u) m = u.getMessageByUID(uid);
        else m = uid >= 1 && uid <= f.getMessageCount() ? f.getMessage((int) uid) : null;
        if (m == null) throw new ActionException("EMAIL.NOT_FOUND", "No email with id " + uid + " in " + f.getFullName(), "The email may have been moved or deleted.");
        return m;
    }

    static long idOf(Folder f, Message m) throws MessagingException { return f instanceof UIDFolder u ? u.getUID(m) : m.getMessageNumber(); }

    /** Removes deleted emails now; POP3 removes them when the session closes instead. */
    private static void expunge(Folder f) throws MessagingException {
        try { f.expunge(); } catch (MethodNotSupportedException ignored) { }
    }

    private static String fromText(Message m) throws MessagingException {
        Address[] a = m.getFrom();
        return a == null || a.length == 0 ? "" : (a[0] instanceof InternetAddress ia ? ia.getAddress() : a[0].toString());
    }

    @Action(id = "list", name = "Find emails", category = "Mailbox", idempotent = true, timeoutSeconds = 300, capabilities = "network",
            description = "List emails in one or more folders, newest first, filtered by read status, sender, subject and date; loop over the table to process them.",
            panel = "folder, status, from_contains, subject_contains, since, before, max_results | to_contains, body_contains, has_attachments_only, oldest_first, local_time, unread_only, read_only, session")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, from, subject, received, unread, has_attachments, cc, sent, attachment_count, folder.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of emails.")
    public Map<String, Object> list(ActionContext ctx,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"INBOX\"", description = D_FOLDERS) String folder,
            @Input(name = "unread_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", visibleWhen = "unread_only=true", description = D_UNREAD_ONLY) boolean unread,
            @Input(name = "from_contains", type = OoaType.STRING, required = false, description = "Sender contains this text; separate several with ; to match any of them.") String from,
            @Input(name = "subject_contains", type = OoaType.STRING, required = false, description = D_SUBJECT) String subject,
            @Input(name = "since", type = OoaType.DATETIME, required = false, description = "Received on or after this date.") LocalDateTime since,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "100", min = 0, description = "Maximum emails returned; 0 = no limit.") int max,
            @Input(name = "before", type = OoaType.DATETIME, required = false, description = "Received before this date.") LocalDateTime before,
            @Input(name = "read_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", visibleWhen = "read_only=true", description = D_READ_ONLY) boolean readOnly,
            @Input(name = "to_contains", type = OoaType.STRING, required = false, description = "A To recipient contains this text.") String to,
            @Input(name = "body_contains", type = OoaType.STRING, required = false, description = "The body contains this text (searched by the server).") String bodyText,
            @Input(name = "has_attachments_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only emails that have attachments.") boolean withAtts,
            @Input(name = "oldest_first", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Return the oldest emails first instead of the newest.") boolean oldestFirst,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"", description = "Session name.") String session,
            @Input(name = "status", type = OoaType.STRING, required = false, defaultValue = "\"any\"", enumValues = {"any", "unread", "read"}, description = D_STATUS) String status,
            @Input(name = "local_time", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = D_LOCAL_TIME) boolean localTime) {
        boolean[] rs = readStatus(status, unread, readOnly);
        List<Object> rows = new ArrayList<>();
        for (Map<String, Object> e : find(ms(ctx, session), folder, rs[0], rs[1], from, subject, since, before, to, bodyText, withAtts, oldestFirst, max, zone(localTime)))
            rows.add(new ArrayList<>(e.values()));
        return Map.of("table", Values.table(SUMMARY_COLUMNS, rows), "count", BigDecimal.valueOf(rows.size()));
    }

    /** Earlier signature, kept for callers written before status and local_time. */
    public Map<String, Object> list(ActionContext ctx, String folder, boolean unread, String from, String subject, LocalDateTime since, int max, LocalDateTime before,
                                    boolean readOnly, String to, String bodyText, boolean withAtts, boolean oldestFirst, String session) {
        return list(ctx, folder, unread, from, subject, since, max, before, readOnly, to, bodyText, withAtts, oldestFirst, session, "any", false);
    }

    static final List<String> SUMMARY_COLUMNS = List.of("id", "from", "subject", "received", "unread", "has_attachments", "cc", "sent", "attachment_count", "folder");

    static final String D_FOLDERS = "Mail folder; several separated by ; and * for any text, e.g. INBOX; Invoices/*.";
    static final String D_STATUS = "Which emails: any, only unread or only read.";
    static final String D_UNREAD_ONLY = "Older steps: only unread emails. New steps choose the status instead.";
    static final String D_READ_ONLY = "Older steps: only emails already read. New steps choose the status instead.";
    static final String D_SUBJECT = "Subject contains this text; in double quotes (\"Invoice 7\") the whole subject must be exactly that; separate several with ; to match any of them.";
    static final String D_LOCAL_TIME = "Give dates (and read since and before) in this computer's time zone instead of UTC.";

    /** Unread-only and read-only from the status choice, or from the older ticks. */
    static boolean[] readStatus(String status, boolean unreadOnly, boolean readOnly) {
        String st = status == null || status.isBlank() ? "any" : status.strip().toLowerCase(Locale.ROOT);
        if (!st.equals("any") && !st.equals("unread") && !st.equals("read"))
            throw new ActionException("EMAIL.BAD_INPUT", "Unknown status '" + status + "'", "Use any, unread or read.");
        return new boolean[]{unreadOnly || st.equals("unread"), readOnly || st.equals("read")};
    }

    static java.time.ZoneId zone(boolean local) { return local ? java.time.ZoneId.systemDefault() : ZoneOffset.UTC; }

    /** The folders a filter names: several separated by ;, each may use * (any text, also in subfolders) or % (any text in one level). */
    static List<String> folderNames(MailSession s, String spec) throws MessagingException {
        String text = spec == null || spec.isBlank() ? "INBOX" : spec;
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String part : text.split(";")) {
            String p = part.strip();
            if (p.isEmpty()) continue;
            if (p.contains("*") || p.contains("%")) {
                for (Folder f : s.store.getDefaultFolder().list(p)) if ((f.getType() & Folder.HOLDS_MESSAGES) != 0) out.add(f.getFullName());
            } else out.add(p);
        }
        if (out.isEmpty()) throw new ActionException("EMAIL.NO_FOLDER", "No mail folder matches '" + text + "'", "Use Get folders to see the exact names.");
        return new ArrayList<>(out);
    }

    /** A subject filter value in double quotes means the whole subject; returns the value without them. */
    static String unquote(String v) {
        String t = v.strip();
        return t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"") ? t.substring(1, t.length() - 1).strip() : t;
    }

    /** True when the subject matches one of the ;-separated values: contains it, or, for a "quoted" value, is exactly it (case ignored). */
    static boolean subjectMatches(String subject, String spec) {
        if (spec == null || spec.isBlank()) return true;
        String s = subject == null ? "" : subject.strip();
        for (String v : spec.split(";")) {
            String t = v.strip();
            if (t.isEmpty()) continue;
            boolean exact = t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"");
            if (exact ? s.equalsIgnoreCase(unquote(t)) : s.toLowerCase(Locale.ROOT).contains(t.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /** The emails of a folder matching the search; a "quoted" subject is then checked exactly (the server only searches for text in it). */
    static Message[] matching(Folder f, SearchTerm t, String subject) throws MessagingException {
        Message[] msgs = matching(f, t);
        if (subject == null || !subject.contains("\"")) return msgs;
        List<Message> keep = new ArrayList<>();
        for (Message m : msgs) if (subjectMatches(m.getSubject(), subject)) keep.add(m);
        return keep.toArray(new Message[0]);
    }

    /** The emails matching the Find emails filters, newest first unless oldest_first, each as id, from, subject, received, unread, has_attachments. */
    static List<Map<String, Object>> find(MailSession s, String folder, boolean unread, boolean readOnly, String from, String subject, LocalDateTime since,
                                          LocalDateTime before, String to, String bodyText, boolean withAtts, boolean oldestFirst, int max) {
        return find(s, folder, unread, readOnly, from, subject, since, before, to, bodyText, withAtts, oldestFirst, max, ZoneOffset.UTC);
    }

    /** One email found: its folder and the message, in the folder's order. */
    private record Hit(String folder, Folder f, Message m, long when, int order) {}

    /**
     * The matching emails of one or more folders (see folderNames), newest first unless oldestFirst; max 0 = no limit. One
     * folder keeps the server's order (arrival); several are merged by received date.
     */
    static List<Map<String, Object>> find(MailSession s, String folder, boolean unread, boolean readOnly, String from, String subject, LocalDateTime since,
                                          LocalDateTime before, String to, String bodyText, boolean withAtts, boolean oldestFirst, int max, java.time.ZoneId zone) {
        try {
            SearchTerm term = filter(unread, readOnly, from, subject, since, before, to, bodyText, s.pop3, zone);
            List<String> names = folderNames(s, folder);
            List<Hit> hits = new ArrayList<>();
            for (String name : names) {
                Folder f = s.folder(name);
                Message[] msgs = matching(f, term, subject);
                FetchProfile fp = new FetchProfile();
                fp.add(FetchProfile.Item.ENVELOPE); fp.add(FetchProfile.Item.FLAGS); fp.add(FetchProfile.Item.CONTENT_INFO); fp.add(UIDFolder.FetchProfileItem.UID);
                f.fetch(msgs, fp);
                for (Message m : msgs) {
                    if (withAtts && !m.isMimeType("multipart/mixed")) continue;
                    Date d = m.getReceivedDate() != null ? m.getReceivedDate() : m.getSentDate();
                    hits.add(new Hit(name, f, m, d == null ? 0 : d.getTime(), hits.size()));
                }
            }
            if (names.size() > 1) hits.sort(Comparator.comparingLong(Hit::when).thenComparingInt(Hit::order));
            if (!oldestFirst) Collections.reverse(hits);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Hit h : hits) {
                if (max > 0 && out.size() >= max) break;
                out.add(summary(idOf(h.f(), h.m()), h.m(), h.folder(), zone));
            }
            return out;
        } catch (MessagingException e) { throw mail(e); }
    }

    /** One row of Find emails, keyed by SUMMARY_COLUMNS. */
    static Map<String, Object> summary(long id, Message m) throws MessagingException {
        return summary(id, m, m.getFolder() == null ? "" : m.getFolder().getFullName(), ZoneOffset.UTC);
    }

    static Map<String, Object> summary(long id, Message m, String folder, java.time.ZoneId zone) throws MessagingException {
        Date d = m.getReceivedDate() != null ? m.getReceivedDate() : m.getSentDate(), sent = m.getSentDate();
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", BigDecimal.valueOf(id));
        e.put("from", fromText(m));
        e.put("subject", Objects.toString(m.getSubject(), ""));
        e.put("received", d == null ? null : LocalDateTime.ofInstant(d.toInstant(), zone));
        e.put("unread", !m.isSet(Flags.Flag.SEEN));
        e.put("has_attachments", m.isMimeType("multipart/mixed"));
        e.put("cc", addressList(m.getRecipients(Message.RecipientType.CC)));
        e.put("sent", sent == null ? null : LocalDateTime.ofInstant(sent.toInstant(), zone));
        e.put("attachment_count", BigDecimal.valueOf(attachmentCount(m)));
        e.put("folder", folder);
        return e;
    }

    /** Attachments of an email, counted from its structure (no body is downloaded); the same parts Save attachments saves. */
    static int attachmentCount(Part p) {
        try {
            if (p.isMimeType("multipart/*")) {
                Multipart mp = (Multipart) p.getContent();
                int n = 0;
                for (int i = 0; i < mp.getCount(); i++) n += attachmentCount(mp.getBodyPart(i));
                return n;
            }
            String disp = p.getDisposition();
            return p.getFileName() != null && (Part.ATTACHMENT.equalsIgnoreCase(disp) || Part.INLINE.equalsIgnoreCase(disp) || !p.isMimeType("text/*")) ? 1 : 0;
        } catch (MessagingException | IOException e) { return 0; }
    }

    @LoopIterator(id = "emails", name = "Each email in a mailbox",
            description = "Yield each email matching the Find emails filters as a dictionary: id, from, subject, received, unread, has_attachments, cc, sent, attachment_count, folder.")
    public Iterable<Object> emails(ActionContext ctx,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"INBOX\"", description = D_FOLDERS) String folder,
            @Input(name = "unread_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", visibleWhen = "unread_only=true", description = D_UNREAD_ONLY) boolean unread,
            @Input(name = "from_contains", type = OoaType.STRING, required = false, description = "Sender contains this text; separate several with ; to match any of them.") String from,
            @Input(name = "subject_contains", type = OoaType.STRING, required = false, description = D_SUBJECT) String subject,
            @Input(name = "since", type = OoaType.DATETIME, required = false, description = "Received on or after this date.") LocalDateTime since,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "100", min = 0, description = "Stop after this many emails; 0 = no limit.") int max,
            @Input(name = "before", type = OoaType.DATETIME, required = false, description = "Received before this date.") LocalDateTime before,
            @Input(name = "read_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", visibleWhen = "read_only=true", description = D_READ_ONLY) boolean readOnly,
            @Input(name = "to_contains", type = OoaType.STRING, required = false, description = "A To recipient contains this text.") String to,
            @Input(name = "body_contains", type = OoaType.STRING, required = false, description = "The body contains this text (searched by the server).") String bodyText,
            @Input(name = "has_attachments_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only emails that have attachments.") boolean withAtts,
            @Input(name = "oldest_first", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Give the oldest emails first instead of the newest.") boolean oldestFirst,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"", description = D_SESSION) String session,
            @Input(name = "with_body", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = com.oneofficeautomation.packages.MailRecord.D_WITH_BODY) boolean withBody,
            @Input(name = "status", type = OoaType.STRING, required = false, defaultValue = "\"any\"", enumValues = {"any", "unread", "read"}, description = D_STATUS) String status,
            @Input(name = "mark_read", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Mark each email read when the loop reaches it (IMAP only).") boolean markRead,
            @Input(name = "local_time", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = D_LOCAL_TIME) boolean localTime) {
        boolean[] rs = readStatus(status, unread, readOnly);
        MailSession s = ms(ctx, session);
        // the list is taken before the loop starts, so emails can be moved or deleted inside it
        List<Map<String, Object>> found = new ArrayList<>(find(s, folder, rs[0], rs[1], from, subject, since, before, to, bodyText, withAtts, oldestFirst, max, zone(localTime)));
        Iterable<Object> each = com.oneofficeautomation.packages.MailRecord.each(found, withBody,
                row -> (Map<String, Object>) get(ctx, ((Number) row.get("id")).longValue(), folderOf(row, folder), false, session, localTime).get("email"));
        if (!markRead || s.pop3) return each;
        return () -> new Iterator<>() {
            final Iterator<Object> it = each.iterator();
            @Override public boolean hasNext() { return it.hasNext(); }
            @Override public Object next() {
                Object o = it.next();
                if (o instanceof Map<?, ?> row && row.get("id") instanceof Number id) {
                    try { byUid(s.folder(folderOf(row, folder)), id.longValue()).setFlag(Flags.Flag.SEEN, true); }
                    catch (MessagingException e) { throw mail(e); }
                }
                return o;
            }
        };
    }

    /** The folder an email row came from (several folders may be searched), else the step's folder. */
    static String folderOf(Map<?, ?> row, String folder) {
        return row.get("folder") instanceof String f && !f.isBlank() ? f : folder == null || folder.isBlank() ? "INBOX" : folder;
    }

    /** Earlier signature, kept for callers written before with_body. */
    public Iterable<Object> emails(ActionContext ctx, String folder, boolean unread, String from, String subject, LocalDateTime since, int max, LocalDateTime before,
                                   boolean readOnly, String to, String bodyText, boolean withAtts, boolean oldestFirst, String session) {
        return emails(ctx, folder, unread, from, subject, since, max, before, readOnly, to, bodyText, withAtts, oldestFirst, session, false);
    }

    /** Earlier signature, kept for callers written before status, mark_read and local_time. */
    public Iterable<Object> emails(ActionContext ctx, String folder, boolean unread, String from, String subject, LocalDateTime since, int max, LocalDateTime before,
                                   boolean readOnly, String to, String bodyText, boolean withAtts, boolean oldestFirst, String session, boolean withBody) {
        return emails(ctx, folder, unread, from, subject, since, max, before, readOnly, to, bodyText, withAtts, oldestFirst, session, withBody, "any", false, false);
    }

    private static void collect(Part p, StringBuilder text, StringBuilder html, List<Part> attachments) throws MessagingException, IOException {
        String disp = p.getDisposition();
        if (p.getFileName() != null && (Part.ATTACHMENT.equalsIgnoreCase(disp) || Part.INLINE.equalsIgnoreCase(disp) || !p.isMimeType("text/*"))) { attachments.add(p); return; }
        if (p.isMimeType("text/plain")) text.append(p.getContent());
        else if (p.isMimeType("text/html")) html.append(p.getContent());
        else if (p.isMimeType("multipart/*")) { Multipart mp = (Multipart) p.getContent(); for (int i = 0; i < mp.getCount(); i++) collect(mp.getBodyPart(i), text, html, attachments); }
    }

    @Action(id = "get", name = "Read email", category = "Mailbox", idempotent = true, capabilities = "network", description = "Read one email: sender, recipients, subject, text, HTML and attachment names.",
            panel = "id, folder, mark_read | local_time, session")
    @Output(name = "email", type = OoaType.DICTIONARY, description = "id, from, to, cc, bcc, subject, received, text, html, attachments, attachment_count, reply_to, sent, message_id, importance, flagged, size. Also, the same on every mail service: id, from, to, cc, subject, received, text, html, attachments (names), has_attachments, unread, message_id.")
    public Map<String, Object> get(ActionContext ctx,
            @Input(name = "id", type = OoaType.NUMBER, required = false, description = D_ID) Long id,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"INBOX\"", description = "Mail folder.") String folder,
            @Input(name = "mark_read", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Mark the email as read (IMAP only).") boolean markRead,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"", description = "Session name.") String session,
            @Input(name = "local_time", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = D_LOCAL_TIME) boolean localTime) {
        try {
            MailSession s = ms(ctx, session);
            Target t = target(ctx, id, folder);
            Message m = byUid(s.folder(t.folder()), t.id());
            boolean wasUnread = !m.isSet(Flags.Flag.SEEN);
            Map<String, Object> e = details(m, t.id(), zone(localTime));
            e.put("unread", wasUnread && !(markRead && !s.pop3));
            if (markRead && !s.pop3) m.setFlag(Flags.Flag.SEEN, true);
            return Map.of("email", com.oneofficeautomation.packages.MailRecord.withCommonKeys(e));
        } catch (MessagingException | IOException e) { throw e instanceof MessagingException me ? mail(me) : new ActionException("EMAIL.READ_FAILED", e.getMessage(), "Check the mail server's address, port and the sign-in in the connection, and that the mailbox exists."); }
    }

    /** Earlier signature, kept for callers written before local_time. */
    public Map<String, Object> get(ActionContext ctx, long id, String folder, boolean markRead, String session) { return get(ctx, Long.valueOf(id), folder, markRead, session, false); }

    /** High, low or normal, from the Importance, Priority or X-Priority header. */
    static String importanceOf(Part m) throws MessagingException {
        String[] imp = m.getHeader("Importance"), pri = m.getHeader("X-Priority"), p2 = m.getHeader("Priority");
        String v = (imp != null ? imp[0] : pri != null ? pri[0] : p2 != null ? p2[0] : "").trim().toLowerCase(Locale.ROOT);
        if (v.startsWith("high") || v.startsWith("urgent") || v.startsWith("1") || v.startsWith("2")) return "high";
        if (v.startsWith("low") || v.startsWith("non-urgent") || v.startsWith("4") || v.startsWith("5")) return "low";
        return "normal";
    }

    private static List<Object> addressList(Address[] a) { return a == null ? List.of() : Arrays.stream(a).map(x -> (Object) x.toString()).toList(); }

    static Map<String, Object> details(Message m, long id) throws MessagingException, IOException { return details(m, id, ZoneOffset.UTC); }

    static Map<String, Object> details(Message m, long id, java.time.ZoneId zone) throws MessagingException, IOException {
        StringBuilder text = new StringBuilder(), html = new StringBuilder();
        List<Part> atts = new ArrayList<>();
        collect(m, text, html, atts);
        List<Object> names = new ArrayList<>();
        for (Part a : atts) names.add(MimeUtility.decodeText(a.getFileName()));
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", BigDecimal.valueOf(id));
        e.put("from", fromText(m));
        e.put("to", addressList(m.getRecipients(Message.RecipientType.TO)));
        e.put("cc", addressList(m.getRecipients(Message.RecipientType.CC)));
        e.put("bcc", addressList(m.getRecipients(Message.RecipientType.BCC)));
        e.put("subject", Objects.toString(m.getSubject(), ""));
        Date d = m.getReceivedDate(), sent = m.getSentDate();
        e.put("received", d == null ? null : LocalDateTime.ofInstant(d.toInstant(), zone));
        e.put("text", text.toString());
        e.put("html", html.toString());
        e.put("attachments", names);
        e.put("attachment_count", BigDecimal.valueOf(names.size()));
        Address[] replyTo = m.getReplyTo();
        e.put("reply_to", addressList(replyTo));
        e.put("sent", sent == null ? null : LocalDateTime.ofInstant(sent.toInstant(), zone));
        e.put("message_id", m instanceof MimeMessage mm ? Objects.toString(mm.getMessageID(), "") : "");
        e.put("importance", importanceOf(m));
        e.put("flagged", m.isSet(Flags.Flag.FLAGGED));
        e.put("size", BigDecimal.valueOf(Math.max(0, m.getSize())));
        return e;
    }

    /** Attachment names come from outside: keep only a safe file name, never a path. */
    static String safeName(String name) {
        String n = name.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[\\x00-\\x1f<>:\"|?*]", "_").strip();
        if (n.isEmpty() || n.equals(".") || n.equals("..")) n = "attachment";
        return n.length() > 150 ? n.substring(n.length() - 150) : n;
    }

    @Action(id = "save_attachments", name = "Save attachments", category = "Mailbox", capabilities = {"network", "filesystem"},
            description = "Save an email's attachments to a folder, optionally only names matching a pattern such as *.pdf.",
            panel = "id, target_folder, overwrite | name_pattern, folder, include_inline, mark_read, session")
    @Output(name = "files", type = OoaType.LIST, description = "Saved file paths.")
    public Map<String, Object> saveAttachments(ActionContext ctx,
            @Input(name = "id", type = OoaType.NUMBER, required = false, description = D_ID) Long id,
            @Input(name = "target_folder", type = OoaType.FILE, description = "Folder to save into.") String target,
            @Input(name = "name_pattern", type = OoaType.STRING, required = false, defaultValue = "\"*\"", description = "Only names matching this pattern, e.g. *.pdf.") String pattern,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"INBOX\"", description = "Mail folder.") String folder,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Replace files of the same name instead of adding a number such as (2).") boolean overwrite,
            @Input(name = "include_inline", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Also save images embedded in the body, such as signature logos.") boolean inline,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"", description = "Session name.") String session,
            @Input(name = "mark_read", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Mark the email read afterwards (IMAP only); otherwise its read status stays as it was.") boolean markRead) {
        try {
            MailSession s = ms(ctx, session);
            Target t = target(ctx, id, folder);
            Message m = byUid(s.folder(t.folder()), t.id());
            Path dir = ctx.resolvePath(target);
            List<Object> saved = saveParts(m, dir, pattern, overwrite, inline);
            if (markRead && !s.pop3) m.setFlag(Flags.Flag.SEEN, true);
            return Map.of("files", saved);
        } catch (MessagingException e) { throw mail(e);
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), "Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it.", true); }
    }

    /** Saves the attachments of one email whose names match the pattern; returns the saved paths. */
    static List<Object> saveParts(Message m, Path dir, String pattern, boolean overwrite, boolean inline) throws MessagingException, IOException {
        List<Part> atts = new ArrayList<>();
        collect(m, new StringBuilder(), new StringBuilder(), atts);
        Files.createDirectories(dir);
        PathMatcher pm = FileSystems.getDefault().getPathMatcher("glob:" + (pattern == null || pattern.isBlank() ? "*" : pattern).toLowerCase(Locale.ROOT));
        List<Object> saved = new ArrayList<>();
        for (Part a : atts) {
            if (!inline && Part.INLINE.equalsIgnoreCase(a.getDisposition())) continue;
            String name = safeName(MimeUtility.decodeText(a.getFileName()));
            if (!pm.matches(Path.of(name.toLowerCase(Locale.ROOT)))) continue;
            Path out = dir.resolve(name);
            if (overwrite) Files.deleteIfExists(out);
            else for (int i = 2; Files.exists(out); i++) out = dir.resolve(name.replaceFirst("(\\.[^.]+)?$", " (" + i + ")$1"));
            try (java.io.InputStream in = a.getInputStream()) { Files.copy(in, out); }
            saved.add(out.toString());
        }
        return saved;
    }

    @Action(id = "save_all_attachments", name = "Save all attachments", category = "Mailbox", timeoutSeconds = 600, capabilities = {"network", "filesystem"},
            description = "Save the attachments of every email in one or more folders that matches the filters, optionally marking those emails as read.",
            panel = "status, mark_read, folder, target_folder, overwrite | name_pattern, from_contains, subject_contains, since, before, unread_only, session")
    @Output(name = "files", type = OoaType.LIST, description = "Saved file paths.")
    @Output(name = "email_count", type = OoaType.NUMBER, description = "Number of emails that matched.")
    public Map<String, Object> saveAllAttachments(ActionContext ctx,
            @Input(name = "target_folder", type = OoaType.FILE, description = "Folder to save into.") String target,
            @Input(name = "name_pattern", type = OoaType.STRING, required = false, defaultValue = "\"*\"", description = "Only names matching this pattern, e.g. *.pdf.") String pattern,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"INBOX\"", description = D_FOLDERS) String folder,
            @Input(name = "unread_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", visibleWhen = "unread_only=true", description = D_UNREAD_ONLY) boolean unread,
            @Input(name = "from_contains", type = OoaType.STRING, required = false, description = "Sender contains this text; separate several with ; to match any of them.") String from,
            @Input(name = "subject_contains", type = OoaType.STRING, required = false, description = D_SUBJECT) String subject,
            @Input(name = "since", type = OoaType.DATETIME, required = false, description = "Received on or after this date.") LocalDateTime since,
            @Input(name = "before", type = OoaType.DATETIME, required = false, description = "Received before this date.") LocalDateTime before,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Replace files of the same name instead of adding a number such as (2).") boolean overwrite,
            @Input(name = "mark_read", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Mark each matching email as read afterwards (IMAP only); otherwise read status stays as it was.") boolean markRead,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"", description = D_SESSION) String session,
            @Input(name = "status", type = OoaType.STRING, required = false, defaultValue = "\"any\"", enumValues = {"any", "unread", "read"}, description = D_STATUS) String status) {
        try {
            MailSession s = ms(ctx, session);
            boolean[] rs = readStatus(status, unread, false);
            SearchTerm term = filter(rs[0], rs[1], from, subject, since, before, null, null, s.pop3);
            Path dir = ctx.resolvePath(target);
            List<Object> saved = new ArrayList<>();
            int count = 0;
            for (String name : folderNames(s, folder)) {
                Message[] msgs = matching(s.folder(name), term, subject);
                count += msgs.length;
                for (Message m : msgs) {
                    saved.addAll(saveParts(m, dir, pattern, overwrite, true));
                    if (markRead && !s.pop3) m.setFlag(Flags.Flag.SEEN, true);
                }
            }
            ctx.info("Saved " + saved.size() + " attachment(s) from " + count + " email(s)");
            return Map.of("files", saved, "email_count", BigDecimal.valueOf(count));
        } catch (MessagingException e) { throw mail(e);
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), "Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it.", true); }
    }

    /** Mark before G5 (the read/unread choice). */
    public Map<String, Object> mark(ActionContext ctx, Long id, boolean read, String folder, Boolean flagged, String session) { return mark(ctx, id, read, folder, flagged, session, null); }

    @Action(id = "mark", name = "Mark read or unread", category = "Mailbox", capabilities = "network", description = "Mark an email as read or unread, and optionally set or clear its follow-up flag.",
            panel = "id, status, read, folder | flagged, session")
    public Map<String, Object> mark(ActionContext ctx,
            @Input(name = "id", type = OoaType.NUMBER, required = false, description = D_ID) Long id,
            @Input(name = "read", type = OoaType.BOOLEAN, required = false, defaultValue = "true", label = "Mark as read (untick: mark as unread)", tone = "plain",
                    description = "Ticked = read, not ticked = unread.") boolean read,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"INBOX\"", description = "Mail folder.") String folder,
            @Input(name = "flagged", type = OoaType.BOOLEAN, required = false, description = "Set (true) or clear (false) the follow-up flag; unchanged when empty.") Boolean flagged,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"", description = "Session name.") String session,
            @Input(name = "status", type = OoaType.STRING, required = false, newStepDefault = "\"read\"", enumValues = {"read", "unread"}, description = "Mark it read or unread (G5); the tick for older steps.") String status) {
        if (status != null && !status.isBlank()) read = !status.strip().equalsIgnoreCase("unread");
        try {
            MailSession s = ms(ctx, session);
            imapOnly(s, "Change status");
            Target t = target(ctx, id, folder);
            Message m = byUid(s.folder(t.folder()), t.id());
            m.setFlag(Flags.Flag.SEEN, read);
            if (flagged != null) m.setFlag(Flags.Flag.FLAGGED, flagged);
        } catch (MessagingException e) { throw mail(e); }
        return Map.of();
    }

    @Action(id = "move", name = "Move email", category = "Mailbox", capabilities = "network", description = "Move an email to another folder, e.g. Processed.",
            panel = "id, folder, to_folder | create_folder, session")
    public Map<String, Object> move(ActionContext ctx,
            @Input(name = "id", type = OoaType.NUMBER, required = false, description = D_ID) Long id,
            @Input(name = "to_folder", type = OoaType.STRING, description = "Destination folder.") String to,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"INBOX\"", description = "Current folder.") String folder,
            @Input(name = "create_folder", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Create the destination folder when it does not exist.") boolean create,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"", description = "Session name.") String session) {
        try {
            MailSession s = ms(ctx, session);
            imapOnly(s, "Move");
            Target t = target(ctx, id, folder);
            Folder src = s.folder(t.folder());
            Message m = byUid(src, t.id());
            Folder dst = destination(s, to, create);
            src.copyMessages(new Message[]{m}, dst);
            m.setFlag(Flags.Flag.DELETED, true);
            src.expunge();
        } catch (MessagingException e) { throw mail(e); }
        return Map.of();
    }

    private static Folder destination(MailSession s, String to, boolean create) throws MessagingException {
        Folder dst = s.store.getFolder(to);
        if (!dst.exists()) {
            if (!create) throw new ActionException("EMAIL.NO_FOLDER", "Mail folder '" + to + "' not found", "Create it in the mailbox first, or set create_folder to true.");
            if (!dst.create(Folder.HOLDS_MESSAGES)) throw new ActionException("EMAIL.NO_FOLDER", "The server did not create mail folder '" + to + "'", "Check the folder name and your rights.");
        }
        return dst;
    }

    @Action(id = "delete", name = "Delete email", category = "Mailbox", capabilities = "network", description = "Delete an email (moved to Trash/Deleted Items when that folder exists), or delete it for good.",
            panel = "id, folder | permanent, session")
    public Map<String, Object> delete(ActionContext ctx,
            @Input(name = "id", type = OoaType.NUMBER, required = false, description = D_ID) Long id,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"INBOX\"", description = "Mail folder.") String folder,
            @Input(name = "permanent", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Delete for good instead of moving to Trash or Deleted Items.") boolean permanent,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"", description = "Session name.") String session) {
        try {
            MailSession s = ms(ctx, session);
            Target at = target(ctx, id, folder);
            if (!permanent && !s.pop3) for (String trash : List.of("Deleted Items", "Trash", "[Gmail]/Trash")) {
                Folder t = s.store.getFolder(trash);
                if (t.exists() && !trash.equalsIgnoreCase(at.folder())) return move(ctx, at.id(), trash, at.folder(), false, session);
            }
            Folder src = s.folder(at.folder());
            byUid(src, at.id()).setFlag(Flags.Flag.DELETED, true);
            expunge(src);
        } catch (MessagingException e) { throw mail(e); }
        return Map.of();
    }

    @Action(id = "get_folders", name = "Get folders", category = "Mailbox", idempotent = true, capabilities = "network", description = "List all mail folders.",
            panel = "session")
    @Output(name = "folders", type = OoaType.LIST, description = "Folder names.")
    public Map<String, Object> getFolders(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"", description = "Session name.") String session) {
        try {
            List<Object> out = new ArrayList<>();
            for (Folder f : ms(ctx, session).store.getDefaultFolder().list("*")) out.add(f.getFullName());
            return Map.of("folders", out);
        } catch (MessagingException e) { throw mail(e); }
    }

    @Action(id = "disconnect", name = "Disconnect", category = "Mailbox", description = "Close the mailbox session.",
            panel = "session")
    public Map<String, Object> disconnect(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"", description = "Session name.") String session) {
        Object o = ctx.sessions().remove(session);
        if (o instanceof MailSession m) try { m.close(); } catch (MessagingException ignored) { }
        return Map.of();
    }

    // ------------------------------------------------------------------ forward, reply, bulk actions and .eml

    private static final String D_SMTP = "SMTP server used to send, e.g. smtp.office365.com; when empty, the outgoing server kept by Connect (smtp_host there).";
    private static final String D_SESSION = "Session name of the open mailbox.";

    /** Messages matching the filter, or every message in the folder when no filter is given. */
    static SearchTerm filter(boolean unread, String from, String subject, LocalDateTime since, LocalDateTime before) {
        return filter(unread, false, from, subject, since, before, null, null, false);
    }

    /** Matches any of the values separated by ; in the text, or null when there is none. */
    static SearchTerm anyOf(String text, java.util.function.Function<String, SearchTerm> term) {
        if (text == null || text.isBlank()) return null;
        List<SearchTerm> t = new ArrayList<>();
        for (String v : text.split(";")) if (!v.isBlank()) t.add(term.apply(v.trim()));
        return t.isEmpty() ? null : t.size() == 1 ? t.get(0) : new OrTerm(t.toArray(new SearchTerm[0]));
    }

    /** Search for the filters; dates compare the sent date on POP3, whose servers keep no received date. */
    static SearchTerm filter(boolean unread, boolean read, String from, String subject, LocalDateTime since, LocalDateTime before,
                             String to, String body, boolean sentDates) {
        return filter(unread, read, from, subject, since, before, to, body, sentDates, ZoneOffset.UTC);
    }

    /** The same, with since and before read in the given time zone; a "quoted" subject is searched as its text. */
    static SearchTerm filter(boolean unread, boolean read, String from, String subject, LocalDateTime since, LocalDateTime before,
                             String to, String body, boolean sentDates, java.time.ZoneId zone) {
        if (unread && read) throw new ActionException("EMAIL.BAD_INPUT", "unread_only and read_only cannot both be true", "Choose one of them.");
        List<SearchTerm> terms = new ArrayList<>();
        if (unread) terms.add(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
        if (read) terms.add(new FlagTerm(new Flags(Flags.Flag.SEEN), true));
        SearchTerm f = anyOf(from, FromStringTerm::new), sj = anyOf(subject, v -> new SubjectTerm(unquote(v)));
        if (f != null) terms.add(f);
        if (sj != null) terms.add(sj);
        if (to != null && !to.isBlank()) terms.add(new RecipientStringTerm(Message.RecipientType.TO, to));
        if (body != null && !body.isBlank()) terms.add(new BodyTerm(body));
        if (since != null) { Date d = Date.from(since.atZone(zone).toInstant()); terms.add(sentDates ? new SentDateTerm(ComparisonTerm.GE, d) : new ReceivedDateTerm(ComparisonTerm.GE, d)); }
        if (before != null) { Date d = Date.from(before.atZone(zone).toInstant()); terms.add(sentDates ? new SentDateTerm(ComparisonTerm.LT, d) : new ReceivedDateTerm(ComparisonTerm.LT, d)); }
        return terms.isEmpty() ? null : terms.size() == 1 ? terms.get(0) : new AndTerm(terms.toArray(new SearchTerm[0]));
    }

    private static Message[] matching(Folder f, SearchTerm t) throws MessagingException { return t == null ? f.getMessages() : f.search(t); }

    private static InternetAddress[] required(Object v, String input) {
        InternetAddress[] a;
        try { a = addresses(v); }
        catch (AddressException e) { throw new ActionException("EMAIL.BAD_ADDRESS", "Invalid email address in " + input + ": " + e.getMessage(), "Check the addresses."); }
        if (a.length == 0) throw new ActionException("EMAIL.NO_RECIPIENT", "Give at least one address in " + input, "Fill in " + input + ".");
        return a;
    }

    private static String escapeHtml(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    private static String htmlToText(String html) { return html.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?i)</p>", "\n").replaceAll("<[^>]+>", "").replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&"); }

    private static String addressText(Address[] a) { return a == null ? "" : String.join(", ", Arrays.stream(a).map(x -> x instanceof InternetAddress ia ? ia.toUnicodeString() : x.toString()).toList()); }

    private static void setBody(MimeMessage m, String body, boolean html, List<MimeBodyPart> extra, String charset) throws MessagingException {
        if (extra.isEmpty()) { m.setText(body, charset, html ? "html" : "plain"); return; }
        MimeMultipart mp = new MimeMultipart();
        MimeBodyPart text = new MimeBodyPart();
        text.setText(body, charset, html ? "html" : "plain");
        mp.addBodyPart(text);
        for (MimeBodyPart p : extra) mp.addBodyPart(p);
        m.setContent(mp);
    }

    private static List<MimeBodyPart> fileParts(ActionContext ctx, List<Object> files) throws MessagingException, IOException { return fileParts(ctx, files, false); }

    /** Attachment parts; a missing file fails the step, or with skipMissing is left out with a warning. */
    static List<MimeBodyPart> fileParts(ActionContext ctx, List<Object> files, boolean skipMissing) throws MessagingException, IOException {
        List<MimeBodyPart> out = new ArrayList<>();
        if (files == null) return out;
        for (Object a : files) {
            if (a == null || Values.text(a).isBlank()) continue;
            Path p = ctx == null ? Path.of(Values.text(a)) : ctx.resolvePath(Values.text(a));
            if (!Files.isRegularFile(p) && skipMissing) { if (ctx != null) ctx.warn("Attachment not found, sent without it: " + p); continue; }
            if (!Files.isRegularFile(p)) throw new ActionException("FILE.NOT_FOUND", "Attachment not found: " + p, "Check the attachment path, or tick skip_missing_attachments.");
            MimeBodyPart part = new MimeBodyPart();
            part.attachFile(p.toFile());
            out.add(part);
        }
        return out;
    }

    /** Image files for an HTML body, each with a Content-ID equal to its file name so the body can use src="cid:name". */
    private static List<MimeBodyPart> inlineParts(ActionContext ctx, List<Object> files) throws MessagingException, IOException {
        List<MimeBodyPart> out = fileParts(ctx, files);
        for (MimeBodyPart p : out) { p.setContentID("<" + p.getFileName() + ">"); p.setDisposition(Part.INLINE); }
        return out;
    }

    /** Importance, reply-to and read-receipt headers, shared by send, forward and reply. */
    static void applyOptions(MimeMessage m, String importance, Object replyTo, boolean receipt) throws MessagingException {
        switch (importance == null || importance.isBlank() ? "normal" : importance.trim().toLowerCase(Locale.ROOT)) {
            case "high" -> { m.setHeader("Importance", "High"); m.setHeader("X-Priority", "1 (Highest)"); }
            case "low" -> { m.setHeader("Importance", "Low"); m.setHeader("X-Priority", "5 (Lowest)"); }
            case "normal" -> { }
            default -> throw new ActionException("EMAIL.BAD_INPUT", "Unknown importance '" + importance + "'", "Use normal, high or low.");
        }
        if (replyTo != null) {
            InternetAddress[] r;
            try { r = addresses(replyTo); }
            catch (AddressException e) { throw new ActionException("EMAIL.BAD_ADDRESS", "Invalid email address in reply_to: " + e.getMessage(), "Check the addresses."); }
            if (r.length > 0) m.setReplyTo(r);
        }
        Address[] from = m.getFrom();
        if (receipt && from != null && from.length > 0) m.setHeader("Disposition-Notification-To", from[0].toString());
    }

    /** Builds a new email: body (with inline images for HTML), attachments, CC, BCC and the optional headers. */
    static MimeMessage buildMessage(Session session, String from, Object to, Object cc, Object bcc, String subject, String body, boolean html,
                                    List<MimeBodyPart> attachments, List<MimeBodyPart> inline, String importance, Object replyTo, boolean receipt)
            throws MessagingException {
        return buildMessage(session, from, to, cc, bcc, subject, body, html, attachments, inline, importance, replyTo, receipt, "UTF-8");
    }

    /** The same, with the character set of the subject and body. */
    static MimeMessage buildMessage(Session session, String from, Object to, Object cc, Object bcc, String subject, String body, boolean html,
                                    List<MimeBodyPart> attachments, List<MimeBodyPart> inline, String importance, Object replyTo, boolean receipt, String charset)
            throws MessagingException {
        MimeMessage msg = new MimeMessage(session);
        // without a sender (an internal relay used without signing in has no user to send as) this used to crash with a NullPointerException
        if (from == null || from.isBlank())
            throw new ActionException("EMAIL.NO_SENDER", "There is no sender address for this email", "Fill in from (or user, which is also used as the sender).");
        msg.setFrom(new InternetAddress(from.strip(), true));
        msg.setRecipients(Message.RecipientType.TO, addresses(to));
        if (cc != null) msg.setRecipients(Message.RecipientType.CC, addresses(cc));
        if (bcc != null) msg.setRecipients(Message.RecipientType.BCC, addresses(bcc));
        if (msg.getAllRecipients() == null || msg.getAllRecipients().length == 0)
            throw new ActionException("EMAIL.BAD_ADDRESS", "The email has no recipient", "Give at least one address in to, cc or bcc.");
        msg.setSubject(subject, charset);
        MimeBodyPart text = new MimeBodyPart();
        text.setText(body == null ? "" : body, charset, html ? "html" : "plain");
        MimeBodyPart main = text;
        if (inline != null && !inline.isEmpty()) {
            if (!html) throw new ActionException("EMAIL.BAD_INPUT", "Inline images need an HTML body", "Choose the HTML or rich text body type and refer to each image as cid:file-name.");
            MimeMultipart related = new MimeMultipart("related");
            related.addBodyPart(text);
            for (MimeBodyPart p : inline) related.addBodyPart(p);
            main = new MimeBodyPart();
            main.setContent(related);
        }
        MimeMultipart mp = new MimeMultipart();
        mp.addBodyPart(main);
        if (attachments != null) for (MimeBodyPart p : attachments) mp.addBodyPart(p);
        msg.setContent(mp);
        msg.setSentDate(new Date());
        applyOptions(msg, importance, replyTo, receipt);
        msg.saveChanges();
        return msg;
    }

    /** Builds a forward of an email: the note and the original inline (with its attachments), or the original attached as an .eml file. */
    static MimeMessage buildForward(Session session, Message orig, String from, Object to, Object cc, String note, boolean html, boolean asAttachment)
            throws MessagingException, IOException {
        return buildForward(session, orig, from, to, cc, null, note, html, asAttachment, List.of(), null);
    }

    /** Forward with BCC, extra files and an importance. */
    static MimeMessage buildForward(Session session, Message orig, String from, Object to, Object cc, Object bcc, String note, boolean html, boolean asAttachment,
                                    List<MimeBodyPart> files, String importance) throws MessagingException, IOException {
        return buildForward(session, orig, from, to, cc, bcc, note, html, asAttachment, files, importance, null);
    }

    static MimeMessage buildForward(Session session, Message orig, String from, Object to, Object cc, Object bcc, String note, boolean html, boolean asAttachment,
                                    List<MimeBodyPart> files, String importance, Object replyTo) throws MessagingException, IOException {
        return buildForward(session, orig, from, to, cc, bcc, note, html, asAttachment, files, importance, replyTo, "UTF-8", null);
    }

    /** Forward with a character set and, when given, a subject of its own instead of Fwd: and the original's. */
    static MimeMessage buildForward(Session session, Message orig, String from, Object to, Object cc, Object bcc, String note, boolean html, boolean asAttachment,
                                    List<MimeBodyPart> files, String importance, Object replyTo, String charset, String ownSubject) throws MessagingException, IOException {
        MimeMessage m = new MimeMessage(session);
        try { m.setFrom(new InternetAddress(from, true)); }
        catch (AddressException e) { throw new ActionException("EMAIL.BAD_ADDRESS", "Invalid sender address: " + e.getMessage(), "Check from or user."); }
        m.setRecipients(Message.RecipientType.TO, required(to, "to"));
        copies(m, cc, bcc);
        String subject = Objects.toString(orig.getSubject(), "");
        m.setSubject(ownSubject != null && !ownSubject.isBlank() ? ownSubject : subject.matches("(?is)^(fwd?|fw):.*") ? subject : "Fwd: " + subject, charset);
        String body = note == null ? "" : note;
        List<MimeBodyPart> extra = new ArrayList<>();
        if (asAttachment) {
            java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
            orig.writeTo(raw);
            MimeBodyPart att = new MimeBodyPart();
            att.setDataHandler(new jakarta.activation.DataHandler(new jakarta.mail.util.ByteArrayDataSource(raw.toByteArray(), "message/rfc822")));
            att.setFileName(safeName((subject.isBlank() ? "message" : subject) + ".eml"));
            att.setDisposition(Part.ATTACHMENT);
            extra.add(att);
        } else {
            StringBuilder text = new StringBuilder(), htm = new StringBuilder();
            List<Part> atts = new ArrayList<>();
            collect(orig, text, htm, atts);
            String[][] info = {{"From", addressText(orig.getFrom())}, {"Date", orig.getSentDate() == null ? "" : orig.getSentDate().toString()}, {"Subject", subject},
                    {"To", addressText(orig.getRecipients(Message.RecipientType.TO))}, {"Cc", addressText(orig.getRecipients(Message.RecipientType.CC))}};
            StringBuilder b = new StringBuilder(body);
            if (html) {
                b.append("<br><br><div>---------- Forwarded message ---------<br>");
                for (String[] i : info) if (!i[1].isBlank()) b.append(i[0]).append(": ").append(escapeHtml(i[1])).append("<br>");
                b.append("</div><br>").append(htm.length() > 0 ? htm : escapeHtml(text.toString()).replace("\n", "<br>"));
            } else {
                b.append("\r\n\r\n---------- Forwarded message ---------\r\n");
                for (String[] i : info) if (!i[1].isBlank()) b.append(i[0]).append(": ").append(i[1]).append("\r\n");
                b.append("\r\n").append(text.length() > 0 ? text : htmlToText(htm.toString()));
            }
            body = b.toString();
            for (Part a : atts) {
                MimeBodyPart copy = new MimeBodyPart();
                copy.setDataHandler(a.getDataHandler());
                copy.setFileName(a.getFileName());
                copy.setDisposition(Part.ATTACHMENT);
                extra.add(copy);
            }
        }
        if (files != null) extra.addAll(files);
        setBody(m, body, html, extra, charset);
        m.setSentDate(new Date());
        applyOptions(m, importance, replyTo, false);
        m.saveChanges();
        return m;
    }

    /** Adds CC and BCC recipients (either may be empty). */
    private static void copies(MimeMessage m, Object cc, Object bcc) throws MessagingException {
        try {
            if (cc != null) { InternetAddress[] a = addresses(cc); if (a.length > 0) m.addRecipients(Message.RecipientType.CC, a); }
            if (bcc != null) { InternetAddress[] a = addresses(bcc); if (a.length > 0) m.addRecipients(Message.RecipientType.BCC, a); }
        } catch (AddressException e) { throw new ActionException("EMAIL.BAD_ADDRESS", "Invalid email address in cc or bcc: " + e.getMessage(), "Check the addresses."); }
    }

    /** Builds a reply (or reply to all) that keeps the conversation: Re: subject, In-Reply-To, References and the quoted original. */
    static MimeMessage buildReply(Message orig, String from, String body, boolean html, boolean all, boolean includeOriginal, List<MimeBodyPart> attachments)
            throws MessagingException, IOException {
        return buildReply(orig, from, body, html, all, includeOriginal, attachments, null, null, null, null, false);
    }

    /** Reply with extra CC and BCC recipients and an importance. */
    static MimeMessage buildReply(Message orig, String from, String body, boolean html, boolean all, boolean includeOriginal, List<MimeBodyPart> attachments,
                                  Object extraCc, Object bcc, String importance, Object newReplyTo, boolean excludeSender) throws MessagingException, IOException {
        return buildReply(orig, from, body, html, all, includeOriginal, attachments, extraCc, bcc, importance, newReplyTo, excludeSender, "UTF-8", null);
    }

    /** Reply with a character set and, when given, a subject of its own instead of Re: and the original's. */
    static MimeMessage buildReply(Message orig, String from, String body, boolean html, boolean all, boolean includeOriginal, List<MimeBodyPart> attachments,
                                  Object extraCc, Object bcc, String importance, Object newReplyTo, boolean excludeSender, String charset, String ownSubject)
            throws MessagingException, IOException {
        MimeMessage m = (MimeMessage) (orig instanceof MimeMessage mm ? mm.reply(false, false) : orig.reply(false));
        InternetAddress me;
        try { me = new InternetAddress(from, true); }
        catch (AddressException e) { throw new ActionException("EMAIL.BAD_ADDRESS", "Invalid sender address: " + e.getMessage(), "Check from or user."); }
        m.setFrom(me);
        String self = me.getAddress().toLowerCase(Locale.ROOT);
        Map<String, Address> to = new LinkedHashMap<>(), cc = new LinkedHashMap<>();
        Address[] replyTo = orig.getReplyTo();
        for (Address a : replyTo == null || replyTo.length == 0 ? orig.getFrom() == null ? new Address[0] : orig.getFrom() : replyTo) to.putIfAbsent(key(a), a);
        if (all) {
            Address[] t = orig.getRecipients(Message.RecipientType.TO), c = orig.getRecipients(Message.RecipientType.CC);
            if (t != null) for (Address a : t) to.putIfAbsent(key(a), a);
            if (c != null) for (Address a : c) if (!to.containsKey(key(a))) cc.putIfAbsent(key(a), a);
            if (to.size() > 1) to.remove(self);
            cc.remove(self);
            if (excludeSender && orig.getFrom() != null) for (Address a : orig.getFrom()) { to.remove(key(a)); cc.remove(key(a)); }
        }
        if (to.isEmpty()) throw new ActionException("EMAIL.NO_RECIPIENT", "The original email has no sender to reply to", "Reply only to messages that have a sender; use Send email for a new message instead.");
        m.setRecipients(Message.RecipientType.TO, to.values().toArray(new Address[0]));
        m.setRecipients(Message.RecipientType.CC, cc.isEmpty() ? null : cc.values().toArray(new Address[0]));
        copies(m, extraCc, bcc);
        String text = body == null ? "" : body;
        if (includeOriginal) {
            StringBuilder t = new StringBuilder(), h = new StringBuilder();
            collect(orig, t, h, new ArrayList<>());
            String intro = "On " + (orig.getSentDate() == null ? "" : orig.getSentDate().toString()) + ", " + addressText(orig.getFrom()) + " wrote:";
            if (html) {
                text += "<br><br><div>" + escapeHtml(intro) + "</div><blockquote style=\"margin:0 0 0 .8ex;border-left:1px solid #ccc;padding-left:1ex\">"
                        + (h.length() > 0 ? h : escapeHtml(t.toString()).replace("\n", "<br>")) + "</blockquote>";
            } else {
                StringBuilder q = new StringBuilder(text).append("\r\n\r\n").append(intro).append("\r\n");
                String src = t.length() > 0 ? t.toString() : htmlToText(h.toString());
                for (String line : src.replace("\r\n", "\n").split("\n", -1)) q.append(line.isEmpty() ? ">" : "> " + line).append("\r\n");
                text = q.toString();
            }
        }
        if (ownSubject != null && !ownSubject.isBlank()) m.setSubject(ownSubject, charset);
        setBody(m, text, html, attachments == null ? List.of() : attachments, charset);
        m.setSentDate(new Date());
        applyOptions(m, importance, newReplyTo, false);
        m.saveChanges();
        return m;
    }

    private static String key(Address a) { return (a instanceof InternetAddress ia ? ia.getAddress() : a.toString()).toLowerCase(Locale.ROOT); }

    /** The .eml path: inside a folder it is named after the subject; without overwrite a free name such as "x (2).eml" is chosen. */
    static Path emlTarget(Path p, boolean isFolder, String subject, boolean overwrite) { return mailTarget(p, isFolder, subject, overwrite, ".eml"); }

    static Path mailTarget(Path p, boolean isFolder, String subject, boolean overwrite, String extension) {
        Path out = isFolder || Files.isDirectory(p) ? p.resolve(safeName((subject == null || subject.isBlank() ? "email" : subject.replaceAll("[/\\\\]", "_")) + extension)) : p;
        if (!overwrite) {
            String name = out.getFileName().toString();
            for (int i = 2; Files.exists(out); i++) out = out.resolveSibling(name.replaceFirst("(\\.[^.]+)?$", " (" + i + ")$1"));
        }
        return out;
    }

    /** Writes an email in RFC 822 form (.eml), creating parent folders. */
    static Path writeEml(Message m, Path p) throws MessagingException, IOException {
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        try (java.io.OutputStream out = Files.newOutputStream(p)) { m.writeTo(out); }
        return p;
    }

    private static void smtpSend(MimeMessage msg, Smtp k) throws MessagingException {
        Session session = k.session();
        try (Transport t = session.getTransport("smtp")) {
            k.connect(t);
            t.sendMessage(msg, msg.getAllRecipients());
        }
    }

    private static String sender(String from, String user) { return from != null && !from.isBlank() ? from : user; }

    /** The email a step means: its own id and folder, or (id left empty inside an Each email loop) the loop's current email, in its folder. */
    record Target(long id, String folder) {}

    static final String D_ID = "Email id from Find emails; empty inside an Each email loop = the loop's current email.";

    static Target target(ActionContext ctx, Long id, String folder) {
        String f = folder == null || folder.isBlank() ? "INBOX" : folder;
        if (id != null) return new Target(id, f);
        Object item = ctx == null ? null : ctx.loopItem();
        if (item instanceof Map<?, ?> m) {
            Object v = m.get("id");
            Long n = v instanceof Number num ? Long.valueOf(num.longValue()) : v instanceof String str && str.strip().matches("\\d+") ? Long.valueOf(str.strip()) : null;
            if (n != null) return new Target(n, m.get("folder") instanceof String lf && !lf.isBlank() ? lf : f);
        }
        throw new ActionException("EMAIL.BAD_INPUT", "Give the email's id, or put this step inside an Each email loop", "Fill in id with an id from Find emails.");
    }

    @Action(id = "forward", name = "Forward email", category = "Send", timeoutSeconds = 120, capabilities = "network",
            description = "Forward an email from the open mailbox with a note, either inline with its attachments or attached as an .eml file.",
            panel = "id, to, cc, bcc, reply_to, attachments, importance, body_type, note, html, [Send through another server: smtp_host, port, security, user, password, access_token] | as_attachment, subject, from, footer, folder, skip_missing_attachments, skip_invalid_addresses, charset, timeout_seconds, session")
    @Output(name = "message_id", type = OoaType.STRING, description = "Message-ID of the sent email.")
    public Map<String, Object> forward(ActionContext ctx,
            @Input(name = "id", type = OoaType.NUMBER, required = false, description = D_ID) Long id,
            @Input(name = "to", description = "Recipient address(es): text separated by , or ; or a List.") Object to,
            @Input(name = "cc", required = false, description = "CC address(es).") Object cc,
            @Input(name = "note", type = OoaType.STRING, required = false, defaultValue = "\"\"", description = "Text placed above the forwarded email.") String note,
            @Input(name = "html", type = OoaType.BOOLEAN, required = false, defaultValue = "false", visibleWhen = "html=true", description = D_HTML) boolean html,
            @Input(name = "as_attachment", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Attach the original as an .eml file instead of quoting it.") boolean asAttachment,
            @Input(name = "smtp_host", type = OoaType.STRING, required = false, example = "smtp.office365.com", description = D_SMTP) String host,
            @Input(name = "port", type = OoaType.NUMBER, required = false, defaultValue = "587", description = "587 for STARTTLS, 465 for SSL.") int port,
            @Input(name = "security", type = OoaType.STRING, required = false, defaultValue = "\"starttls\"", enumValues = {"starttls", "ssl", "none"}, description = "Connection security.") String security,
            @Input(name = "user", type = OoaType.STRING, required = false, description = "Mailbox user name for SMTP sign-in; the session's user when empty.") String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password or app password from the vault.") Object password,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = D_TOKEN) Object token,
            @Input(name = "from", type = OoaType.STRING, required = false, description = "Sender address; the user when empty.") String from,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"INBOX\"", description = "Mail folder of the email.") String folder,
            @Input(name = "bcc", required = false, description = "BCC address(es).") Object bcc,
            @Input(name = "attachments", type = OoaType.LIST, required = false, description = "More file paths to attach.") List<Object> attachments,
            @Input(name = "importance", type = OoaType.STRING, required = false, defaultValue = "\"normal\"", enumValues = {"normal", "high", "low"}, description = "Importance of the forwarded email.") String importance,
            @Input(name = "reply_to", required = false, description = "Address(es) that replies go to, when not the sender.") Object replyTo,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"", description = D_SESSION) String session,
            @Input(name = "body_type", type = OoaType.STRING, required = false, defaultValue = "\"plain\"", enumValues = {"plain", "html", "rich"}, description = D_BODY_TYPE) String bodyType,
            @Input(name = "subject", type = OoaType.STRING, required = false, description = "Subject of the forward; Fwd: and the original subject when empty.") String subject,
            @Input(name = "footer", type = OoaType.STRING, required = false, description = D_FOOTER) String footer,
            @Input(name = "skip_missing_attachments", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = D_SKIP_MISSING) boolean skipMissing,
            @Input(name = "skip_invalid_addresses", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = D_SKIP_INVALID) boolean skipInvalid,
            @Input(name = "charset", type = OoaType.STRING, required = false, defaultValue = "\"UTF-8\"", example = "ISO-8859-1", description = D_CHARSET) String charset,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "60", newStepDefault = "120", min = 1, max = 600, description = D_SEND_TIMEOUT) int timeout) {
        try {
            Target t = target(ctx, id, folder);
            Message orig = byUid(ms(ctx, session).folder(t.folder()), t.id());
            Smtp k = smtp(ctx, session, host, port, security, user, password, token);
            Session s = k.session(timeout, skipInvalid);
            boolean asHtml = isHtml(bodyType, html);
            MimeMessage msg = buildForward(s, orig, sender(from, k.user()), skipInvalid ? validOnly(ctx, to, "to") : to, skipInvalid ? validOnly(ctx, cc, "cc") : cc,
                    skipInvalid ? validOnly(ctx, bcc, "bcc") : bcc, withFooter(note, footer, asHtml), asHtml, asAttachment,
                    fileParts(ctx, attachments, skipMissing), importance, replyTo, charset(charset), subject);
            transmit(ctx, msg, k, s, skipInvalid);
            ctx.info("Forwarded email " + t.id() + " to " + addressText(msg.getRecipients(Message.RecipientType.TO)));
            return Map.of("message_id", Objects.toString(msg.getMessageID(), ""));
        } catch (MessagingException e) { throw mail(e);
        } catch (IOException e) { throw new ActionException("EMAIL.READ_FAILED", e.getMessage(), "Check the mail server's address, port and the sign-in in the connection, and that the mailbox exists."); }
    }

    /** What reply and reply to all send, besides the reply itself. */
    record ReplyOptions(String bodyType, String subject, String footer, boolean skipMissing, boolean skipInvalid, String charset, int timeout) {
        static final ReplyOptions OLD = new ReplyOptions("plain", null, null, false, false, "UTF-8", 60);
    }

    private Map<String, Object> replyImpl(ActionContext ctx, Long id, String body, boolean html, boolean all, boolean include, List<Object> attachments,
                                          String host, int port, String security, String user, Object password, Object token, String from, String folder,
                                          Object cc, Object bcc, String importance, Object replyTo, boolean excludeSender, String session, ReplyOptions o) {
        try {
            Target t = target(ctx, id, folder);
            Message orig = byUid(ms(ctx, session).folder(t.folder()), t.id());
            Smtp k = smtp(ctx, session, host, port, security, user, password, token);
            Session s = k.session(o.timeout(), o.skipInvalid());
            boolean asHtml = isHtml(o.bodyType(), html);
            MimeMessage msg = buildReply(orig, sender(from, k.user()), withFooter(body, o.footer(), asHtml), asHtml, all, include, fileParts(ctx, attachments, o.skipMissing()),
                    o.skipInvalid() ? validOnly(ctx, cc, "cc") : cc, o.skipInvalid() ? validOnly(ctx, bcc, "bcc") : bcc, importance, replyTo, excludeSender,
                    charset(o.charset()), o.subject());
            transmit(ctx, msg, k, s, o.skipInvalid());
            try { orig.setFlag(Flags.Flag.ANSWERED, true); } catch (MessagingException ignored) { }
            ctx.info("Replied to email " + t.id());
            return Map.of("message_id", Objects.toString(msg.getMessageID(), ""));
        } catch (MessagingException e) { throw mail(e);
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), "Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it.", true); }
    }

    @Action(id = "reply", name = "Reply to email", category = "Send", timeoutSeconds = 120, capabilities = "network",
            description = "Reply to the sender of an email in the open mailbox, keeping it in the same conversation and quoting the original.",
            panel = "id, cc, bcc, reply_to, attachments, importance, body_type, body, html, [Send through another server: smtp_host, port, security, user, password, access_token] | include_original, subject, from, footer, folder, skip_missing_attachments, skip_invalid_addresses, charset, timeout_seconds, session")
    @Output(name = "message_id", type = OoaType.STRING, description = "Message-ID of the reply.")
    public Map<String, Object> reply(ActionContext ctx,
            @Input(name = "id", type = OoaType.NUMBER, required = false, description = D_ID) Long id,
            @Input(name = "body", type = OoaType.STRING, required = false, defaultValue = "\"\"", description = "Reply text.") String body,
            @Input(name = "html", type = OoaType.BOOLEAN, required = false, defaultValue = "false", visibleWhen = "html=true", description = D_HTML) boolean html,
            @Input(name = "include_original", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Quote the original email below the reply.") boolean include,
            @Input(name = "attachments", type = OoaType.LIST, required = false, description = "File paths to attach.") List<Object> attachments,
            @Input(name = "smtp_host", type = OoaType.STRING, required = false, example = "smtp.office365.com", description = D_SMTP) String host,
            @Input(name = "port", type = OoaType.NUMBER, required = false, defaultValue = "587", description = "587 for STARTTLS, 465 for SSL.") int port,
            @Input(name = "security", type = OoaType.STRING, required = false, defaultValue = "\"starttls\"", enumValues = {"starttls", "ssl", "none"}, description = "Connection security.") String security,
            @Input(name = "user", type = OoaType.STRING, required = false, description = "Mailbox user name for SMTP sign-in; the session's user when empty.") String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password or app password from the vault.") Object password,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = D_TOKEN) Object token,
            @Input(name = "from", type = OoaType.STRING, required = false, description = "Sender address; the user when empty.") String from,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"INBOX\"", description = "Mail folder of the email.") String folder,
            @Input(name = "cc", required = false, description = "More CC address(es).") Object cc,
            @Input(name = "bcc", required = false, description = "BCC address(es).") Object bcc,
            @Input(name = "importance", type = OoaType.STRING, required = false, defaultValue = "\"normal\"", enumValues = {"normal", "high", "low"}, description = "Importance of the reply.") String importance,
            @Input(name = "reply_to", required = false, description = "Address(es) that replies go to, when not the sender.") Object replyTo,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"", description = D_SESSION) String session,
            @Input(name = "body_type", type = OoaType.STRING, required = false, defaultValue = "\"plain\"", enumValues = {"plain", "html", "rich"}, description = D_BODY_TYPE) String bodyType,
            @Input(name = "subject", type = OoaType.STRING, required = false, description = D_REPLY_SUBJECT) String subject,
            @Input(name = "footer", type = OoaType.STRING, required = false, description = D_FOOTER) String footer,
            @Input(name = "skip_missing_attachments", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = D_SKIP_MISSING) boolean skipMissing,
            @Input(name = "skip_invalid_addresses", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = D_SKIP_INVALID) boolean skipInvalid,
            @Input(name = "charset", type = OoaType.STRING, required = false, defaultValue = "\"UTF-8\"", example = "ISO-8859-1", description = D_CHARSET) String charset,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "60", newStepDefault = "120", min = 1, max = 600, description = D_SEND_TIMEOUT) int timeout) {
        return replyImpl(ctx, id, body, html, false, include, attachments, host, port, security, user, password, token, from, folder, cc, bcc, importance, replyTo, false, session,
                new ReplyOptions(bodyType, subject, footer, skipMissing, skipInvalid, charset, timeout));
    }

    static final String D_REPLY_SUBJECT = "Subject of the reply; Re: and the original subject when empty.";

    @Action(id = "reply_all", name = "Reply to all", category = "Send", timeoutSeconds = 120, capabilities = "network",
            description = "Reply to the sender and all other recipients of an email, keeping it in the same conversation and quoting the original.",
            panel = "id, cc, bcc, reply_to, exclude_sender, importance, attachments, body_type, body, html, [Send through another server: smtp_host, port, security, user, password, access_token] | include_original, subject, from, footer, folder, skip_missing_attachments, skip_invalid_addresses, charset, timeout_seconds, session")
    @Output(name = "message_id", type = OoaType.STRING, description = "Message-ID of the reply.")
    public Map<String, Object> replyAll(ActionContext ctx,
            @Input(name = "id", type = OoaType.NUMBER, required = false, description = D_ID) Long id,
            @Input(name = "body", type = OoaType.STRING, required = false, defaultValue = "\"\"", description = "Reply text.") String body,
            @Input(name = "html", type = OoaType.BOOLEAN, required = false, defaultValue = "false", visibleWhen = "html=true", description = D_HTML) boolean html,
            @Input(name = "include_original", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Quote the original email below the reply.") boolean include,
            @Input(name = "attachments", type = OoaType.LIST, required = false, description = "File paths to attach.") List<Object> attachments,
            @Input(name = "smtp_host", type = OoaType.STRING, required = false, example = "smtp.office365.com", description = D_SMTP) String host,
            @Input(name = "port", type = OoaType.NUMBER, required = false, defaultValue = "587", description = "587 for STARTTLS, 465 for SSL.") int port,
            @Input(name = "security", type = OoaType.STRING, required = false, defaultValue = "\"starttls\"", enumValues = {"starttls", "ssl", "none"}, description = "Connection security.") String security,
            @Input(name = "user", type = OoaType.STRING, required = false, description = "Mailbox user name for SMTP sign-in; the session's user when empty.") String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password or app password from the vault.") Object password,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = D_TOKEN) Object token,
            @Input(name = "from", type = OoaType.STRING, required = false, description = "Sender address; the user when empty; also left out of the recipients.") String from,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"INBOX\"", description = "Mail folder of the email.") String folder,
            @Input(name = "cc", required = false, description = "More CC address(es).") Object cc,
            @Input(name = "bcc", required = false, description = "BCC address(es).") Object bcc,
            @Input(name = "importance", type = OoaType.STRING, required = false, defaultValue = "\"normal\"", enumValues = {"normal", "high", "low"}, description = "Importance of the reply.") String importance,
            @Input(name = "reply_to", required = false, description = "Address(es) that replies go to, when not the sender.") Object replyTo,
            @Input(name = "exclude_sender", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Leave the original sender out and reply only to the other recipients.") boolean excludeSender,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"", description = D_SESSION) String session,
            @Input(name = "body_type", type = OoaType.STRING, required = false, defaultValue = "\"plain\"", enumValues = {"plain", "html", "rich"}, description = D_BODY_TYPE) String bodyType,
            @Input(name = "subject", type = OoaType.STRING, required = false, description = D_REPLY_SUBJECT) String subject,
            @Input(name = "footer", type = OoaType.STRING, required = false, description = D_FOOTER) String footer,
            @Input(name = "skip_missing_attachments", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = D_SKIP_MISSING) boolean skipMissing,
            @Input(name = "skip_invalid_addresses", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = D_SKIP_INVALID) boolean skipInvalid,
            @Input(name = "charset", type = OoaType.STRING, required = false, defaultValue = "\"UTF-8\"", example = "ISO-8859-1", description = D_CHARSET) String charset,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "60", newStepDefault = "120", min = 1, max = 600, description = D_SEND_TIMEOUT) int timeout) {
        return replyImpl(ctx, id, body, html, true, include, attachments, host, port, security, user, password, token, from, folder, cc, bcc, importance, replyTo, excludeSender, session,
                new ReplyOptions(bodyType, subject, footer, skipMissing, skipInvalid, charset, timeout));
    }

    @Action(id = "delete_all", name = "Delete all matching emails", category = "Mailbox", timeoutSeconds = 600, capabilities = "network",
            description = "Delete every email in one or more folders that matches the filters (all emails when no filter is set).",
            panel = "folder, status, subject_contains, from_contains, since, before | permanent, expunge, to_contains, body_contains, unread_only, read_only, session")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of emails deleted.")
    public Map<String, Object> deleteAll(ActionContext ctx,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"INBOX\"", description = D_FOLDERS) String folder,
            @Input(name = "unread_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", visibleWhen = "unread_only=true", description = D_UNREAD_ONLY) boolean unread,
            @Input(name = "from_contains", type = OoaType.STRING, required = false, description = "Sender contains this text; separate several with ; to match any of them.") String from,
            @Input(name = "subject_contains", type = OoaType.STRING, required = false, description = D_SUBJECT) String subject,
            @Input(name = "since", type = OoaType.DATETIME, required = false, description = "Received on or after this date.") LocalDateTime since,
            @Input(name = "before", type = OoaType.DATETIME, required = false, description = "Received before this date.") LocalDateTime before,
            @Input(name = "permanent", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Delete for good instead of moving to Trash or Deleted Items.") boolean permanent,
            @Input(name = "expunge", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Permanent mode: remove the emails now; when false they are only marked deleted until the session closes.") boolean expunge,
            @Input(name = "read_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", visibleWhen = "read_only=true", description = D_READ_ONLY) boolean readOnly,
            @Input(name = "to_contains", type = OoaType.STRING, required = false, description = "A To recipient contains this text.") String toText,
            @Input(name = "body_contains", type = OoaType.STRING, required = false, description = "The body contains this text (searched by the server).") String bodyText,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"", description = D_SESSION) String session,
            @Input(name = "status", type = OoaType.STRING, required = false, defaultValue = "\"any\"", enumValues = {"any", "unread", "read"}, description = D_STATUS) String status) {
        try {
            MailSession s = ms(ctx, session);
            boolean[] rs = readStatus(status, unread, readOnly);
            SearchTerm term = filter(rs[0], rs[1], from, subject, since, before, toText, bodyText, s.pop3);
            int count = 0;
            for (String name : folderNames(s, folder)) {
                Folder src = s.folder(name);
                Message[] msgs = matching(src, term, subject);
                if (msgs.length == 0) continue;
                boolean now = expunge;
                if (!permanent && !s.pop3) for (String trash : List.of("Deleted Items", "Trash", "[Gmail]/Trash")) {
                    Folder t = s.store.getFolder(trash);
                    if (t.exists() && !trash.equalsIgnoreCase(name)) { src.copyMessages(msgs, t); now = true; break; }
                }
                src.setFlags(msgs, new Flags(Flags.Flag.DELETED), true);
                if (now) expunge(src);
                count += msgs.length;
                ctx.info("Deleted " + msgs.length + " email(s) from " + name);
            }
            return Map.of("count", BigDecimal.valueOf(count));
        } catch (MessagingException e) { throw mail(e); }
    }

    @Action(id = "move_all", name = "Move all matching emails", category = "Mailbox", timeoutSeconds = 600, capabilities = "network",
            description = "Move every email in one or more folders that matches the filters to another folder.",
            panel = "to_folder, status, folder, subject_contains, from_contains, since, before | to_contains, body_contains, create_folder, unread_only, read_only, session")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of emails moved.")
    public Map<String, Object> moveAll(ActionContext ctx,
            @Input(name = "to_folder", type = OoaType.STRING, description = "Destination folder.") String to,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"INBOX\"", description = "Current folder; several separated by ; and * for any text, e.g. INBOX; Invoices/*.") String folder,
            @Input(name = "unread_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", visibleWhen = "unread_only=true", description = D_UNREAD_ONLY) boolean unread,
            @Input(name = "from_contains", type = OoaType.STRING, required = false, description = "Sender contains this text; separate several with ; to match any of them.") String from,
            @Input(name = "subject_contains", type = OoaType.STRING, required = false, description = D_SUBJECT) String subject,
            @Input(name = "since", type = OoaType.DATETIME, required = false, description = "Received on or after this date.") LocalDateTime since,
            @Input(name = "before", type = OoaType.DATETIME, required = false, description = "Received before this date.") LocalDateTime before,
            @Input(name = "read_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", visibleWhen = "read_only=true", description = D_READ_ONLY) boolean readOnly,
            @Input(name = "to_contains", type = OoaType.STRING, required = false, description = "A To recipient contains this text.") String toText,
            @Input(name = "body_contains", type = OoaType.STRING, required = false, description = "The body contains this text (searched by the server).") String bodyText,
            @Input(name = "create_folder", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Create the destination folder when it does not exist.") boolean create,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"", description = D_SESSION) String session,
            @Input(name = "status", type = OoaType.STRING, required = false, defaultValue = "\"any\"", enumValues = {"any", "unread", "read"}, description = D_STATUS) String status) {
        try {
            MailSession s = ms(ctx, session);
            imapOnly(s, "Move all");
            boolean[] rs = readStatus(status, unread, readOnly);
            SearchTerm term = filter(rs[0], rs[1], from, subject, since, before, toText, bodyText, false);
            Folder dst = destination(s, to, create);
            int count = 0;
            for (String name : folderNames(s, folder)) {
                if (name.equalsIgnoreCase(dst.getFullName())) continue;
                Folder src = s.folder(name);
                Message[] msgs = matching(src, term, subject);
                if (msgs.length == 0) continue;
                src.copyMessages(msgs, dst);
                src.setFlags(msgs, new Flags(Flags.Flag.DELETED), true);
                src.expunge();
                count += msgs.length;
                ctx.info("Moved " + msgs.length + " email(s) from " + name + " to " + to);
            }
            return Map.of("count", BigDecimal.valueOf(count));
        } catch (MessagingException e) { throw mail(e); }
    }

    @Action(id = "folder_exists", name = "Check folder exists", category = "Mailbox", idempotent = true, capabilities = "network",
            description = "Check whether a mail folder exists in the open mailbox.",
            panel = "folder | session")
    @Output(name = "exists", type = OoaType.BOOLEAN, description = "True when the folder exists.")
    public Map<String, Object> folderExists(ActionContext ctx,
            @Input(name = "folder", type = OoaType.STRING, example = "Processed", description = "Folder name; use / or the server's separator for subfolders.") String folder,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"", description = D_SESSION) String session) {
        if (folder == null || folder.isBlank()) throw new ActionException("EMAIL.BAD_INPUT", "Give a folder name", "Fill in the folder's name.");
        try { return Map.of("exists", ms(ctx, session).store.getFolder(folder).exists()); }
        catch (MessagingException e) { throw mail(e); }
    }

    @Action(id = "save_email_as_msg", name = "Save email as .msg", category = "Mailbox", capabilities = {"network", "filesystem"},
            description = "Save an email, with its recipients, HTML body and attachments, as an Outlook .msg file.",
            panel = "id, folder, local_path, file_name, overwrite | session")
    @Output(name = "path", type = OoaType.FILE, description = "Saved local path.")
    @Output(name = "attachments", type = OoaType.NUMBER, description = "Number of attachments saved in the file.")
    public Map<String, Object> saveMsg(ActionContext ctx,
            @Input(name = "id", type = OoaType.NUMBER, required = false, description = D_ID) Long id,
            @Input(name = "local_path", type = OoaType.FILE, required = false, example = "mail/", description = D_SAVE_PATH + ".msg file.") String local,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"INBOX\"", description = "Mail folder.") String folder,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", newStepDefault = "false", description = "Replace an existing file; when false a number such as (2) is added to the name.") boolean overwrite,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"", description = D_SESSION) String session,
            @Input(name = "file_name", type = OoaType.STRING, required = false, description = D_FILE_NAME) String fileName) {
        try {
            Target t = target(ctx, id, folder);
            Message m = byUid(ms(ctx, session).folder(t.folder()), t.id());
            com.oneofficeautomation.packages.MsgFile.Message msg = toMsg(m);
            Path p = saveTarget(ctx, local, fileName, m.getSubject(), overwrite, ".msg");
            com.oneofficeautomation.packages.MsgFile.write(msg, p);
            return Map.of("path", p.toString(), "attachments", BigDecimal.valueOf(msg.attachments.size()));
        }
        catch (MessagingException e) { throw mail(e);
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), "Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it.", true); }
    }

    static final String D_SAVE_PATH = "Folder or file to save into (a path ending in / or \\ is a folder); empty = a temporary file of this run. In a folder the file is named after the subject (or file name); the step gives the path. Saves the ";
    static final String D_FILE_NAME = "Name of the file in the folder; the email's subject when empty.";

    /** Where Save email writes: a folder (named after the file name or the subject), a file, or a temporary file when no path is given. */
    static Path saveTarget(ActionContext ctx, String local, String fileName, String subject, boolean overwrite, String extension) {
        String base = fileName != null && !fileName.isBlank() ? fileName.strip() : subject == null || subject.isBlank() ? "email" : subject;
        String name = safeName(base.replaceAll("[/\\\\]", "_") + (base.toLowerCase(Locale.ROOT).endsWith(extension) ? "" : extension));
        if (local == null || local.isBlank()) return mailTarget(ctx.outputPath(null, name), false, subject, overwrite, extension);
        boolean folder = local.endsWith("/") || local.endsWith("\\") || (fileName != null && !fileName.isBlank());
        Path p = ctx.resolvePath(local);
        return folder || Files.isDirectory(p) ? mailTarget(p.resolve(name), false, subject, overwrite, extension) : mailTarget(p, false, subject, overwrite, extension);
    }

    /** The parts of a MIME message an Outlook .msg file holds. */
    static com.oneofficeautomation.packages.MsgFile.Message toMsg(Message m) throws MessagingException, IOException {
        var out = new com.oneofficeautomation.packages.MsgFile.Message();
        out.subject = m.getSubject() == null ? "" : m.getSubject();
        Address[] from = m.getFrom();
        if (from != null && from.length > 0) out.from = msgAddress(from[0]);
        for (Message.RecipientType t : List.of(Message.RecipientType.TO, Message.RecipientType.CC, Message.RecipientType.BCC)) {
            Address[] list = m.getRecipients(t);
            if (list != null) for (Address a : list) (t == Message.RecipientType.TO ? out.to : t == Message.RecipientType.CC ? out.cc : out.bcc).add(msgAddress(a));
        }
        out.sent = m.getSentDate() == null ? null : m.getSentDate().toInstant();
        out.received = m.getReceivedDate() == null ? null : m.getReceivedDate().toInstant();
        String[] id = m.getHeader("Message-ID");
        out.messageId = id == null || id.length == 0 ? null : id[0];
        String[] imp = m.getHeader("Importance"), prio = m.getHeader("X-Priority");
        String level = imp != null && imp.length > 0 ? imp[0].trim().toLowerCase(Locale.ROOT) : prio != null && prio.length > 0 ? (prio[0].trim().startsWith("1") || prio[0].trim().startsWith("2") ? "high" : prio[0].trim().startsWith("4") || prio[0].trim().startsWith("5") ? "low" : "normal") : "normal";
        out.importance = level;
        out.read = m.isSet(Flags.Flag.SEEN);
        collectParts(m, out);
        return out;
    }

    private static com.oneofficeautomation.packages.MsgFile.Address msgAddress(Address a) {
        if (a instanceof InternetAddress ia) return new com.oneofficeautomation.packages.MsgFile.Address(ia.getPersonal() == null ? "" : ia.getPersonal(), ia.getAddress());
        return new com.oneofficeautomation.packages.MsgFile.Address("", a.toString());
    }

    /** Walks the MIME tree: the first text/plain and text/html bodies, every other leaf part as an attachment. */
    private static void collectParts(Part p, com.oneofficeautomation.packages.MsgFile.Message out) throws MessagingException, IOException {
        if (p.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) p.getContent();
            for (int i = 0; i < mp.getCount(); i++) collectParts(mp.getBodyPart(i), out);
            return;
        }
        String disposition = p.getDisposition();
        boolean attachment = Part.ATTACHMENT.equalsIgnoreCase(disposition) || (p.getFileName() != null && !p.isMimeType("text/*"));
        if (!attachment && p.isMimeType("text/plain") && out.bodyText == null) { out.bodyText = String.valueOf(p.getContent()); return; }
        if (!attachment && p.isMimeType("text/html") && out.bodyHtml == null) { out.bodyHtml = String.valueOf(p.getContent()); return; }
        byte[] data;
        try (java.io.InputStream in = p.getInputStream()) { data = in.readAllBytes(); }
        String cid = p instanceof MimePart mp && mp.getContentID() != null ? mp.getContentID().replaceAll("^<|>$", "") : null;
        String name = p.getFileName() != null ? MimeUtility.decodeText(p.getFileName()) : p.isMimeType("message/rfc822") ? "message.eml" : "part" + (out.attachments.size() + 1);
        String type = p.getContentType() == null ? "application/octet-stream" : p.getContentType().split(";")[0].trim();
        out.attachments.add(new com.oneofficeautomation.packages.MsgFile.Attachment(name, type, data, cid, Part.INLINE.equalsIgnoreCase(disposition) && cid != null));
    }

    @Action(id = "save_email_as_eml", name = "Save email as .eml", category = "Mailbox", capabilities = {"network", "filesystem"},
            description = "Save a complete email, with attachments, as an .eml file that mail programs can open.",
            panel = "id, folder, local_path, file_name, overwrite | session")
    @Output(name = "path", type = OoaType.FILE, description = "Saved local path.")
    public Map<String, Object> saveEml(ActionContext ctx,
            @Input(name = "id", type = OoaType.NUMBER, required = false, description = D_ID) Long id,
            @Input(name = "local_path", type = OoaType.FILE, required = false, example = "mail/", description = D_SAVE_PATH + ".eml file.") String local,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"INBOX\"", description = "Mail folder.") String folder,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", newStepDefault = "false", description = "Replace an existing file; when false a number such as (2) is added to the name.") boolean overwrite,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"", description = D_SESSION) String session,
            @Input(name = "file_name", type = OoaType.STRING, required = false, description = D_FILE_NAME) String fileName) {
        try {
            Target t = target(ctx, id, folder);
            Message m = byUid(ms(ctx, session).folder(t.folder()), t.id());
            return Map.of("path", writeEml(m, saveTarget(ctx, local, fileName, m.getSubject(), overwrite, ".eml")).toString());
        }
        catch (MessagingException e) { throw mail(e);
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), "Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it.", true); }
    }

}
