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

        Session session() {
            Properties p = props("smtp", host, port, security, token != null);
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
        if (e instanceof AuthenticationFailedException) return new ActionException("EMAIL.AUTH_FAILED", "Mail sign-in failed: " + m,
                "Check the user and password or token; Microsoft 365 and Gmail need OAuth or an app password.");
        if (m.contains("connect") || m.contains("timed out") || m.contains("UnknownHost"))
            return new ActionException("EMAIL.UNREACHABLE", "Cannot reach the mail server: " + m, "Check host, port, security and network access.", true);
        return new ActionException("EMAIL.ERROR", m, null);
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
            panel = "to, cc, bcc, reply_to, importance, subject, attachments, body, html, [Send through a specific server: smtp_host, port, security, user, password, access_token] | from, read_receipt, inline_images, session")
    @Output(name = "message_id", type = OoaType.STRING, description = "Message-ID of the sent email.")
    public Map<String, Object> send(ActionContext ctx,
            @Input(name = "smtp_host", type = OoaType.STRING, required = false, example = "smtp.office365.com", description = D_SMTP) String host,
            @Input(name = "port", type = OoaType.NUMBER, required = false, defaultValue = "587", description = "587 for STARTTLS, 465 for SSL.") int port,
            @Input(name = "security", type = OoaType.STRING, required = false, defaultValue = "\"starttls\"", enumValues = {"starttls", "ssl", "none"}, description = "Connection security.") String security,
            @Input(name = "user", type = OoaType.STRING, required = false, description = "Mailbox user name; the mailbox session's user when empty.") String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false,
                    description = "Password or app password from the vault; with neither password nor token the server is used without signing in (internal relay).") Object password,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = "OAuth 2.0 access token (Microsoft 365, Gmail) from the vault.") Object token,
            @Input(name = "from", type = OoaType.STRING, required = false, description = "Sender address; the user when empty.") String from,
            @Input(name = "to", description = "Recipient address(es): text separated by , or ; or a List.") Object to,
            @Input(name = "cc", required = false, description = "CC address(es).") Object cc,
            @Input(name = "bcc", required = false, description = "BCC address(es).") Object bcc,
            @Input(name = "subject", type = OoaType.STRING, description = "Subject.") String subject,
            @Input(name = "body", type = OoaType.STRING, required = false, defaultValue = "\"\"", description = "Message body.") String body,
            @Input(name = "html", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Body is HTML.") boolean html,
            @Input(name = "attachments", type = OoaType.LIST, required = false, description = "File paths to attach.") List<Object> attachments,
            @Input(name = "importance", type = OoaType.STRING, required = false, defaultValue = "\"normal\"", enumValues = {"normal", "high", "low"},
                    description = "Importance shown by the recipient's mail program.") String importance,
            @Input(name = "reply_to", required = false, description = "Address(es) that replies go to, when not the sender.") Object replyTo,
            @Input(name = "read_receipt", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Ask the recipient's mail program to confirm when the email is opened.") boolean receipt,
            @Input(name = "inline_images", type = OoaType.LIST, required = false,
                    description = "HTML body only: image files embedded in the email; refer to each in the body as cid: plus its file name, e.g. <img src=\"cid:logo.png\">.") List<Object> inline,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"",
                    description = "Mailbox session whose outgoing server is used when smtp_host is empty.") String mailSession) {
        Smtp k = smtp(ctx, mailSession, host, port, security, user, password, token);
        Session session = k.session();
        try {
            MimeMessage msg = buildMessage(session, from != null && !from.isBlank() ? from : k.user(), to, cc, bcc, subject, body, html,
                    fileParts(ctx, attachments), inlineParts(ctx, inline), importance, replyTo, receipt);
            try (Transport t = session.getTransport("smtp")) {
                k.connect(t);
                t.sendMessage(msg, msg.getAllRecipients());
            }
            ctx.info("Sent '" + subject + "' to " + Arrays.toString(msg.getRecipients(Message.RecipientType.TO)));
            return Map.of("message_id", Objects.toString(msg.getMessageID(), ""));
        } catch (AddressException e) {
            throw new ActionException("EMAIL.BAD_ADDRESS", "Invalid email address: " + e.getMessage(), "Check the recipient addresses.");
        } catch (MessagingException e) { throw mail(e);
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true); }
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
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = "OAuth 2.0 access token from the vault.") Object token,
            @Input(name = "protocol", type = OoaType.STRING, required = false, defaultValue = "\"imap\"", enumValues = {"imap", "pop3"},
                    description = "imap keeps folders and read status on the server; pop3 reads only the inbox and cannot mark or move emails.") String protocol,
            @Input(name = "security", type = OoaType.STRING, required = false, defaultValue = "\"ssl\"", enumValues = {"ssl", "starttls", "none"}, description = "Connection security.") String security,
            @Input(name = "shared_mailbox", type = OoaType.STRING, required = false, example = "invoices@acme.com",
                    description = "Open this shared mailbox with the user's own sign-in (Exchange and Microsoft 365; the user needs full access to it).") String shared,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "15", min = 1, max = 300, description = "How long to wait for the server to answer when connecting.") int timeout,
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
            description = "List emails in a folder, newest first, filtered by unread, sender, subject and date; loop over the table to process them.",
            panel = "folder, unread_only, read_only, from_contains, subject_contains, since, before, max_results | to_contains, body_contains, has_attachments_only, oldest_first, session")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, from, subject, received, unread, has_attachments.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of emails.")
    public Map<String, Object> list(ActionContext ctx,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"INBOX\"", description = "Mail folder.") String folder,
            @Input(name = "unread_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only unread emails.") boolean unread,
            @Input(name = "from_contains", type = OoaType.STRING, required = false, description = "Sender contains this text; separate several with ; to match any of them.") String from,
            @Input(name = "subject_contains", type = OoaType.STRING, required = false, description = "Subject contains this text; separate several with ; to match any of them.") String subject,
            @Input(name = "since", type = OoaType.DATETIME, required = false, description = "Received on or after this date.") LocalDateTime since,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "100", description = "Maximum emails returned.") int max,
            @Input(name = "before", type = OoaType.DATETIME, required = false, description = "Received before this date.") LocalDateTime before,
            @Input(name = "read_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only emails already read.") boolean readOnly,
            @Input(name = "to_contains", type = OoaType.STRING, required = false, description = "A To recipient contains this text.") String to,
            @Input(name = "body_contains", type = OoaType.STRING, required = false, description = "The body contains this text (searched by the server).") String bodyText,
            @Input(name = "has_attachments_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only emails that have attachments.") boolean withAtts,
            @Input(name = "oldest_first", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Return the oldest emails first instead of the newest.") boolean oldestFirst,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"", description = "Session name.") String session) {
        List<Object> rows = new ArrayList<>();
        for (Map<String, Object> e : find(ms(ctx, session), folder, unread, readOnly, from, subject, since, before, to, bodyText, withAtts, oldestFirst, max))
            rows.add(new ArrayList<>(e.values()));
        return Map.of("table", Values.table(SUMMARY_COLUMNS, rows), "count", BigDecimal.valueOf(rows.size()));
    }

    static final List<String> SUMMARY_COLUMNS = List.of("id", "from", "subject", "received", "unread", "has_attachments");

    /** The emails matching the Find emails filters, newest first unless oldest_first, each as id, from, subject, received, unread, has_attachments. */
    static List<Map<String, Object>> find(MailSession s, String folder, boolean unread, boolean readOnly, String from, String subject, LocalDateTime since,
                                          LocalDateTime before, String to, String bodyText, boolean withAtts, boolean oldestFirst, int max) {
        try {
            Folder f = s.folder(folder);
            Message[] msgs = matching(f, filter(unread, readOnly, from, subject, since, before, to, bodyText, s.pop3));
            FetchProfile fp = new FetchProfile();
            fp.add(FetchProfile.Item.ENVELOPE); fp.add(FetchProfile.Item.FLAGS); fp.add(FetchProfile.Item.CONTENT_INFO); fp.add(UIDFolder.FetchProfileItem.UID);
            f.fetch(msgs, fp);
            List<Map<String, Object>> out = new ArrayList<>();
            for (int k = 0; k < msgs.length && out.size() < max; k++) {
                Message m = msgs[oldestFirst ? k : msgs.length - 1 - k];
                if (withAtts && !m.isMimeType("multipart/mixed")) continue;
                out.add(summary(idOf(f, m), m));
            }
            return out;
        } catch (MessagingException e) { throw mail(e); }
    }

    /** One row of Find emails, keyed by SUMMARY_COLUMNS. */
    static Map<String, Object> summary(long id, Message m) throws MessagingException {
        Date d = m.getReceivedDate() != null ? m.getReceivedDate() : m.getSentDate();
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", BigDecimal.valueOf(id));
        e.put("from", fromText(m));
        e.put("subject", Objects.toString(m.getSubject(), ""));
        e.put("received", d == null ? null : LocalDateTime.ofInstant(d.toInstant(), ZoneOffset.UTC));
        e.put("unread", !m.isSet(Flags.Flag.SEEN));
        e.put("has_attachments", m.isMimeType("multipart/mixed"));
        return e;
    }

    @LoopIterator(id = "emails", name = "Each email in a mailbox",
            description = "Yield each email matching the Find emails filters as a dictionary: id, from, subject, received, unread, has_attachments.")
    public Iterable<Object> emails(ActionContext ctx,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"INBOX\"", description = "Mail folder.") String folder,
            @Input(name = "unread_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only unread emails.") boolean unread,
            @Input(name = "from_contains", type = OoaType.STRING, required = false, description = "Sender contains this text; separate several with ; to match any of them.") String from,
            @Input(name = "subject_contains", type = OoaType.STRING, required = false, description = "Subject contains this text; separate several with ; to match any of them.") String subject,
            @Input(name = "since", type = OoaType.DATETIME, required = false, description = "Received on or after this date.") LocalDateTime since,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "100", min = 1, description = "Stop after this many emails.") int max,
            @Input(name = "before", type = OoaType.DATETIME, required = false, description = "Received before this date.") LocalDateTime before,
            @Input(name = "read_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only emails already read.") boolean readOnly,
            @Input(name = "to_contains", type = OoaType.STRING, required = false, description = "A To recipient contains this text.") String to,
            @Input(name = "body_contains", type = OoaType.STRING, required = false, description = "The body contains this text (searched by the server).") String bodyText,
            @Input(name = "has_attachments_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only emails that have attachments.") boolean withAtts,
            @Input(name = "oldest_first", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Give the oldest emails first instead of the newest.") boolean oldestFirst,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"", description = D_SESSION) String session) {
        // the list is taken before the loop starts, so emails can be moved or deleted inside it
        return new ArrayList<>(find(ms(ctx, session), folder, unread, readOnly, from, subject, since, before, to, bodyText, withAtts, oldestFirst, max));
    }

    private static void collect(Part p, StringBuilder text, StringBuilder html, List<Part> attachments) throws MessagingException, IOException {
        String disp = p.getDisposition();
        if (p.getFileName() != null && (Part.ATTACHMENT.equalsIgnoreCase(disp) || Part.INLINE.equalsIgnoreCase(disp) || !p.isMimeType("text/*"))) { attachments.add(p); return; }
        if (p.isMimeType("text/plain")) text.append(p.getContent());
        else if (p.isMimeType("text/html")) html.append(p.getContent());
        else if (p.isMimeType("multipart/*")) { Multipart mp = (Multipart) p.getContent(); for (int i = 0; i < mp.getCount(); i++) collect(mp.getBodyPart(i), text, html, attachments); }
    }

    @Action(id = "get", name = "Read email", category = "Mailbox", idempotent = true, capabilities = "network", description = "Read one email: sender, recipients, subject, text, HTML and attachment names.",
            panel = "id, folder, mark_read | session")
    @Output(name = "email", type = OoaType.DICTIONARY, description = "id, from, to, cc, subject, received, text, html, attachments, reply_to, sent, message_id, importance, flagged, size.")
    public Map<String, Object> get(ActionContext ctx,
            @Input(name = "id", type = OoaType.NUMBER, description = "Email id from Find emails.") long id,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"INBOX\"", description = "Mail folder.") String folder,
            @Input(name = "mark_read", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Mark the email as read (IMAP only).") boolean markRead,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"", description = "Session name.") String session) {
        try {
            MailSession s = ms(ctx, session);
            Message m = byUid(s.folder(folder), id);
            Map<String, Object> e = details(m, id);
            if (markRead && !s.pop3) m.setFlag(Flags.Flag.SEEN, true);
            return Map.of("email", e);
        } catch (MessagingException | IOException e) { throw e instanceof MessagingException me ? mail(me) : new ActionException("EMAIL.READ_FAILED", e.getMessage()); }
    }

    /** High, low or normal, from the Importance, Priority or X-Priority header. */
    static String importanceOf(Part m) throws MessagingException {
        String[] imp = m.getHeader("Importance"), pri = m.getHeader("X-Priority"), p2 = m.getHeader("Priority");
        String v = (imp != null ? imp[0] : pri != null ? pri[0] : p2 != null ? p2[0] : "").trim().toLowerCase(Locale.ROOT);
        if (v.startsWith("high") || v.startsWith("urgent") || v.startsWith("1") || v.startsWith("2")) return "high";
        if (v.startsWith("low") || v.startsWith("non-urgent") || v.startsWith("4") || v.startsWith("5")) return "low";
        return "normal";
    }

    private static List<Object> addressList(Address[] a) { return a == null ? List.of() : Arrays.stream(a).map(x -> (Object) x.toString()).toList(); }

    static Map<String, Object> details(Message m, long id) throws MessagingException, IOException {
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
        e.put("subject", Objects.toString(m.getSubject(), ""));
        Date d = m.getReceivedDate(), sent = m.getSentDate();
        e.put("received", d == null ? null : LocalDateTime.ofInstant(d.toInstant(), ZoneOffset.UTC));
        e.put("text", text.toString());
        e.put("html", html.toString());
        e.put("attachments", names);
        Address[] replyTo = m.getReplyTo();
        e.put("reply_to", addressList(replyTo));
        e.put("sent", sent == null ? null : LocalDateTime.ofInstant(sent.toInstant(), ZoneOffset.UTC));
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
            panel = "id, target_folder, overwrite | name_pattern, folder, include_inline, session")
    @Output(name = "files", type = OoaType.LIST, description = "Saved file paths.")
    public Map<String, Object> saveAttachments(ActionContext ctx,
            @Input(name = "id", type = OoaType.NUMBER, description = "Email id.") long id,
            @Input(name = "target_folder", type = OoaType.FILE, description = "Folder to save into.") String target,
            @Input(name = "name_pattern", type = OoaType.STRING, required = false, defaultValue = "\"*\"", description = "Only names matching this pattern, e.g. *.pdf.") String pattern,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"INBOX\"", description = "Mail folder.") String folder,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Replace files of the same name instead of adding a number such as (2).") boolean overwrite,
            @Input(name = "include_inline", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Also save images embedded in the body, such as signature logos.") boolean inline,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"", description = "Session name.") String session) {
        try {
            Message m = byUid(ms(ctx, session).folder(folder), id);
            Path dir = ctx.resolvePath(target);
            return Map.of("files", saveParts(m, dir, pattern, overwrite, inline));
        } catch (MessagingException e) { throw mail(e);
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true); }
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

    @Action(id = "save_all_attachments", name = "Save attachments of all matching emails", category = "Mailbox", timeoutSeconds = 600, capabilities = {"network", "filesystem"},
            description = "Save the attachments of every email in a folder that matches the filters, optionally marking those emails as read.",
            panel = "unread_only, mark_read, folder, target_folder, overwrite | name_pattern, from_contains, subject_contains, since, before, session")
    @Output(name = "files", type = OoaType.LIST, description = "Saved file paths.")
    @Output(name = "email_count", type = OoaType.NUMBER, description = "Number of emails that matched.")
    public Map<String, Object> saveAllAttachments(ActionContext ctx,
            @Input(name = "target_folder", type = OoaType.FILE, description = "Folder to save into.") String target,
            @Input(name = "name_pattern", type = OoaType.STRING, required = false, defaultValue = "\"*\"", description = "Only names matching this pattern, e.g. *.pdf.") String pattern,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"INBOX\"", description = "Mail folder.") String folder,
            @Input(name = "unread_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only unread emails.") boolean unread,
            @Input(name = "from_contains", type = OoaType.STRING, required = false, description = "Sender contains this text; separate several with ; to match any of them.") String from,
            @Input(name = "subject_contains", type = OoaType.STRING, required = false, description = "Subject contains this text; separate several with ; to match any of them.") String subject,
            @Input(name = "since", type = OoaType.DATETIME, required = false, description = "Received on or after this date.") LocalDateTime since,
            @Input(name = "before", type = OoaType.DATETIME, required = false, description = "Received before this date.") LocalDateTime before,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Replace files of the same name instead of adding a number such as (2).") boolean overwrite,
            @Input(name = "mark_read", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Mark each matching email as read afterwards (IMAP only).") boolean markRead,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"", description = D_SESSION) String session) {
        try {
            MailSession s = ms(ctx, session);
            Folder f = s.folder(folder);
            Message[] msgs = matching(f, filter(unread, false, from, subject, since, before, null, null, s.pop3));
            Path dir = ctx.resolvePath(target);
            List<Object> saved = new ArrayList<>();
            for (Message m : msgs) {
                saved.addAll(saveParts(m, dir, pattern, overwrite, true));
                if (markRead && !s.pop3) m.setFlag(Flags.Flag.SEEN, true);
            }
            ctx.info("Saved " + saved.size() + " attachment(s) from " + msgs.length + " email(s)");
            return Map.of("files", saved, "email_count", BigDecimal.valueOf(msgs.length));
        } catch (MessagingException e) { throw mail(e);
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true); }
    }

    @Action(id = "mark", name = "Mark read or unread", category = "Mailbox", capabilities = "network", description = "Mark an email as read or unread, and optionally set or clear its follow-up flag.",
            panel = "id, read, folder | flagged, session")
    public Map<String, Object> mark(ActionContext ctx,
            @Input(name = "id", type = OoaType.NUMBER, description = "Email id.") long id,
            @Input(name = "read", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "True = read, false = unread.") boolean read,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"INBOX\"", description = "Mail folder.") String folder,
            @Input(name = "flagged", type = OoaType.BOOLEAN, required = false, description = "Set (true) or clear (false) the follow-up flag; unchanged when empty.") Boolean flagged,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"", description = "Session name.") String session) {
        try {
            MailSession s = ms(ctx, session);
            imapOnly(s, "Change status");
            Message m = byUid(s.folder(folder), id);
            m.setFlag(Flags.Flag.SEEN, read);
            if (flagged != null) m.setFlag(Flags.Flag.FLAGGED, flagged);
        } catch (MessagingException e) { throw mail(e); }
        return Map.of();
    }

    @Action(id = "move", name = "Move email", category = "Mailbox", capabilities = "network", description = "Move an email to another folder, e.g. Processed.",
            panel = "id, folder, to_folder | create_folder, session")
    public Map<String, Object> move(ActionContext ctx,
            @Input(name = "id", type = OoaType.NUMBER, description = "Email id.") long id,
            @Input(name = "to_folder", type = OoaType.STRING, description = "Destination folder.") String to,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"INBOX\"", description = "Current folder.") String folder,
            @Input(name = "create_folder", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Create the destination folder when it does not exist.") boolean create,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"", description = "Session name.") String session) {
        try {
            MailSession s = ms(ctx, session);
            imapOnly(s, "Move");
            Folder src = s.folder(folder);
            Message m = byUid(src, id);
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
            @Input(name = "id", type = OoaType.NUMBER, description = "Email id.") long id,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"INBOX\"", description = "Mail folder.") String folder,
            @Input(name = "permanent", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Delete for good instead of moving to Trash or Deleted Items.") boolean permanent,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"", description = "Session name.") String session) {
        try {
            MailSession s = ms(ctx, session);
            if (!permanent && !s.pop3) for (String trash : List.of("Deleted Items", "Trash", "[Gmail]/Trash")) {
                Folder t = s.store.getFolder(trash);
                if (t.exists() && !trash.equalsIgnoreCase(folder)) return move(ctx, id, trash, folder, false, session);
            }
            Folder src = s.folder(folder);
            byUid(src, id).setFlag(Flags.Flag.DELETED, true);
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
        if (unread && read) throw new ActionException("EMAIL.BAD_INPUT", "unread_only and read_only cannot both be true", "Choose one of them.");
        List<SearchTerm> terms = new ArrayList<>();
        if (unread) terms.add(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
        if (read) terms.add(new FlagTerm(new Flags(Flags.Flag.SEEN), true));
        SearchTerm f = anyOf(from, FromStringTerm::new), sj = anyOf(subject, SubjectTerm::new);
        if (f != null) terms.add(f);
        if (sj != null) terms.add(sj);
        if (to != null && !to.isBlank()) terms.add(new RecipientStringTerm(Message.RecipientType.TO, to));
        if (body != null && !body.isBlank()) terms.add(new BodyTerm(body));
        if (since != null) { Date d = Date.from(since.toInstant(ZoneOffset.UTC)); terms.add(sentDates ? new SentDateTerm(ComparisonTerm.GE, d) : new ReceivedDateTerm(ComparisonTerm.GE, d)); }
        if (before != null) { Date d = Date.from(before.toInstant(ZoneOffset.UTC)); terms.add(sentDates ? new SentDateTerm(ComparisonTerm.LT, d) : new ReceivedDateTerm(ComparisonTerm.LT, d)); }
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

    private static void setBody(MimeMessage m, String body, boolean html, List<MimeBodyPart> extra) throws MessagingException {
        if (extra.isEmpty()) { m.setText(body, "UTF-8", html ? "html" : "plain"); return; }
        MimeMultipart mp = new MimeMultipart();
        MimeBodyPart text = new MimeBodyPart();
        text.setText(body, "UTF-8", html ? "html" : "plain");
        mp.addBodyPart(text);
        for (MimeBodyPart p : extra) mp.addBodyPart(p);
        m.setContent(mp);
    }

    private static List<MimeBodyPart> fileParts(ActionContext ctx, List<Object> files) throws MessagingException, IOException {
        List<MimeBodyPart> out = new ArrayList<>();
        if (files == null) return out;
        for (Object a : files) {
            Path p = ctx == null ? Path.of(Values.text(a)) : ctx.resolvePath(Values.text(a));
            if (!Files.isRegularFile(p)) throw new ActionException("FILE.NOT_FOUND", "Attachment not found: " + p, "Check the attachment path.");
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
        MimeMessage msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress(from, true));
        msg.setRecipients(Message.RecipientType.TO, addresses(to));
        if (cc != null) msg.setRecipients(Message.RecipientType.CC, addresses(cc));
        if (bcc != null) msg.setRecipients(Message.RecipientType.BCC, addresses(bcc));
        msg.setSubject(subject, "UTF-8");
        MimeBodyPart text = new MimeBodyPart();
        text.setText(body == null ? "" : body, "UTF-8", html ? "html" : "plain");
        MimeBodyPart main = text;
        if (inline != null && !inline.isEmpty()) {
            if (!html) throw new ActionException("EMAIL.BAD_INPUT", "Inline images need an HTML body", "Set html to true and refer to each image as cid:file-name.");
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
        MimeMessage m = new MimeMessage(session);
        try { m.setFrom(new InternetAddress(from, true)); }
        catch (AddressException e) { throw new ActionException("EMAIL.BAD_ADDRESS", "Invalid sender address: " + e.getMessage(), "Check from or user."); }
        m.setRecipients(Message.RecipientType.TO, required(to, "to"));
        copies(m, cc, bcc);
        String subject = Objects.toString(orig.getSubject(), "");
        m.setSubject(subject.matches("(?is)^(fwd?|fw):.*") ? subject : "Fwd: " + subject, "UTF-8");
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
        setBody(m, body, html, extra);
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
        if (to.isEmpty()) throw new ActionException("EMAIL.NO_RECIPIENT", "The original email has no sender to reply to", null);
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
        setBody(m, text, html, attachments == null ? List.of() : attachments);
        m.setSentDate(new Date());
        applyOptions(m, importance, newReplyTo, false);
        m.saveChanges();
        return m;
    }

    private static String key(Address a) { return (a instanceof InternetAddress ia ? ia.getAddress() : a.toString()).toLowerCase(Locale.ROOT); }

    /** The .eml path: inside a folder it is named after the subject; without overwrite a free name such as "x (2).eml" is chosen. */
    static Path emlTarget(Path p, boolean isFolder, String subject, boolean overwrite) {
        Path out = isFolder || Files.isDirectory(p) ? p.resolve(safeName((subject == null || subject.isBlank() ? "email" : subject.replaceAll("[/\\\\]", "_")) + ".eml")) : p;
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

    @Action(id = "forward", name = "Forward email", category = "Send", timeoutSeconds = 120, capabilities = "network",
            description = "Forward an email from the open mailbox with a note, either inline with its attachments or attached as an .eml file.",
            panel = "id, to, cc, bcc, reply_to, attachments, importance, note, html, [Send through another server: smtp_host, port, security, user, password, access_token] | as_attachment, from, folder, session")
    @Output(name = "message_id", type = OoaType.STRING, description = "Message-ID of the sent email.")
    public Map<String, Object> forward(ActionContext ctx,
            @Input(name = "id", type = OoaType.NUMBER, description = "Email id from Find emails.") long id,
            @Input(name = "to", description = "Recipient address(es): text separated by , or ; or a List.") Object to,
            @Input(name = "cc", required = false, description = "CC address(es).") Object cc,
            @Input(name = "note", type = OoaType.STRING, required = false, defaultValue = "\"\"", description = "Text placed above the forwarded email.") String note,
            @Input(name = "html", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Note is HTML.") boolean html,
            @Input(name = "as_attachment", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Attach the original as an .eml file instead of quoting it.") boolean asAttachment,
            @Input(name = "smtp_host", type = OoaType.STRING, required = false, example = "smtp.office365.com", description = D_SMTP) String host,
            @Input(name = "port", type = OoaType.NUMBER, required = false, defaultValue = "587", description = "587 for STARTTLS, 465 for SSL.") int port,
            @Input(name = "security", type = OoaType.STRING, required = false, defaultValue = "\"starttls\"", enumValues = {"starttls", "ssl", "none"}, description = "Connection security.") String security,
            @Input(name = "user", type = OoaType.STRING, required = false, description = "Mailbox user name for SMTP sign-in; the session's user when empty.") String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password or app password from the vault.") Object password,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = "OAuth 2.0 access token from the vault.") Object token,
            @Input(name = "from", type = OoaType.STRING, required = false, description = "Sender address; the user when empty.") String from,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"INBOX\"", description = "Mail folder of the email.") String folder,
            @Input(name = "bcc", required = false, description = "BCC address(es).") Object bcc,
            @Input(name = "attachments", type = OoaType.LIST, required = false, description = "More file paths to attach.") List<Object> attachments,
            @Input(name = "importance", type = OoaType.STRING, required = false, defaultValue = "\"normal\"", enumValues = {"normal", "high", "low"}, description = "Importance of the forwarded email.") String importance,
            @Input(name = "reply_to", required = false, description = "Address(es) that replies go to, when not the sender.") Object replyTo,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"", description = D_SESSION) String session) {
        try {
            Message orig = byUid(ms(ctx, session).folder(folder), id);
            Smtp k = smtp(ctx, session, host, port, security, user, password, token);
            MimeMessage msg = buildForward(k.session(), orig, sender(from, k.user()), to, cc, bcc, note, html, asAttachment,
                    fileParts(ctx, attachments), importance, replyTo);
            smtpSend(msg, k);
            ctx.info("Forwarded email " + id + " to " + addressText(msg.getRecipients(Message.RecipientType.TO)));
            return Map.of("message_id", Objects.toString(msg.getMessageID(), ""));
        } catch (MessagingException e) { throw mail(e);
        } catch (IOException e) { throw new ActionException("EMAIL.READ_FAILED", e.getMessage()); }
    }

    private Map<String, Object> replyImpl(ActionContext ctx, long id, String body, boolean html, boolean all, boolean include, List<Object> attachments,
                                          String host, int port, String security, String user, Object password, Object token, String from, String folder,
                                          Object cc, Object bcc, String importance, Object replyTo, boolean excludeSender, String session) {
        try {
            Message orig = byUid(ms(ctx, session).folder(folder), id);
            Smtp k = smtp(ctx, session, host, port, security, user, password, token);
            MimeMessage msg = buildReply(orig, sender(from, k.user()), body, html, all, include, fileParts(ctx, attachments), cc, bcc, importance, replyTo, excludeSender);
            smtpSend(msg, k);
            try { orig.setFlag(Flags.Flag.ANSWERED, true); } catch (MessagingException ignored) { }
            ctx.info("Replied to email " + id);
            return Map.of("message_id", Objects.toString(msg.getMessageID(), ""));
        } catch (MessagingException e) { throw mail(e);
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true); }
    }

    @Action(id = "reply", name = "Reply to email", category = "Send", timeoutSeconds = 120, capabilities = "network",
            description = "Reply to the sender of an email in the open mailbox, keeping it in the same conversation and quoting the original.",
            panel = "id, cc, bcc, reply_to, attachments, importance, body, html, [Send through another server: smtp_host, port, security, user, password, access_token] | include_original, from, folder, session")
    @Output(name = "message_id", type = OoaType.STRING, description = "Message-ID of the reply.")
    public Map<String, Object> reply(ActionContext ctx,
            @Input(name = "id", type = OoaType.NUMBER, description = "Email id from Find emails.") long id,
            @Input(name = "body", type = OoaType.STRING, required = false, defaultValue = "\"\"", description = "Reply text.") String body,
            @Input(name = "html", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Body is HTML.") boolean html,
            @Input(name = "include_original", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Quote the original email below the reply.") boolean include,
            @Input(name = "attachments", type = OoaType.LIST, required = false, description = "File paths to attach.") List<Object> attachments,
            @Input(name = "smtp_host", type = OoaType.STRING, required = false, example = "smtp.office365.com", description = D_SMTP) String host,
            @Input(name = "port", type = OoaType.NUMBER, required = false, defaultValue = "587", description = "587 for STARTTLS, 465 for SSL.") int port,
            @Input(name = "security", type = OoaType.STRING, required = false, defaultValue = "\"starttls\"", enumValues = {"starttls", "ssl", "none"}, description = "Connection security.") String security,
            @Input(name = "user", type = OoaType.STRING, required = false, description = "Mailbox user name for SMTP sign-in; the session's user when empty.") String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password or app password from the vault.") Object password,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = "OAuth 2.0 access token from the vault.") Object token,
            @Input(name = "from", type = OoaType.STRING, required = false, description = "Sender address; the user when empty.") String from,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"INBOX\"", description = "Mail folder of the email.") String folder,
            @Input(name = "cc", required = false, description = "More CC address(es).") Object cc,
            @Input(name = "bcc", required = false, description = "BCC address(es).") Object bcc,
            @Input(name = "importance", type = OoaType.STRING, required = false, defaultValue = "\"normal\"", enumValues = {"normal", "high", "low"}, description = "Importance of the reply.") String importance,
            @Input(name = "reply_to", required = false, description = "Address(es) that replies go to, when not the sender.") Object replyTo,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"", description = D_SESSION) String session) {
        return replyImpl(ctx, id, body, html, false, include, attachments, host, port, security, user, password, token, from, folder, cc, bcc, importance, replyTo, false, session);
    }

    @Action(id = "reply_all", name = "Reply to all", category = "Send", timeoutSeconds = 120, capabilities = "network",
            description = "Reply to the sender and all other recipients of an email, keeping it in the same conversation and quoting the original.",
            panel = "id, cc, bcc, reply_to, exclude_sender, importance, attachments, body, html, [Send through another server: smtp_host, port, security, user, password, access_token] | include_original, from, folder, session")
    @Output(name = "message_id", type = OoaType.STRING, description = "Message-ID of the reply.")
    public Map<String, Object> replyAll(ActionContext ctx,
            @Input(name = "id", type = OoaType.NUMBER, description = "Email id from Find emails.") long id,
            @Input(name = "body", type = OoaType.STRING, required = false, defaultValue = "\"\"", description = "Reply text.") String body,
            @Input(name = "html", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Body is HTML.") boolean html,
            @Input(name = "include_original", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Quote the original email below the reply.") boolean include,
            @Input(name = "attachments", type = OoaType.LIST, required = false, description = "File paths to attach.") List<Object> attachments,
            @Input(name = "smtp_host", type = OoaType.STRING, required = false, example = "smtp.office365.com", description = D_SMTP) String host,
            @Input(name = "port", type = OoaType.NUMBER, required = false, defaultValue = "587", description = "587 for STARTTLS, 465 for SSL.") int port,
            @Input(name = "security", type = OoaType.STRING, required = false, defaultValue = "\"starttls\"", enumValues = {"starttls", "ssl", "none"}, description = "Connection security.") String security,
            @Input(name = "user", type = OoaType.STRING, required = false, description = "Mailbox user name for SMTP sign-in; the session's user when empty.") String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password or app password from the vault.") Object password,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = "OAuth 2.0 access token from the vault.") Object token,
            @Input(name = "from", type = OoaType.STRING, required = false, description = "Sender address; the user when empty; also left out of the recipients.") String from,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"INBOX\"", description = "Mail folder of the email.") String folder,
            @Input(name = "cc", required = false, description = "More CC address(es).") Object cc,
            @Input(name = "bcc", required = false, description = "BCC address(es).") Object bcc,
            @Input(name = "importance", type = OoaType.STRING, required = false, defaultValue = "\"normal\"", enumValues = {"normal", "high", "low"}, description = "Importance of the reply.") String importance,
            @Input(name = "reply_to", required = false, description = "Address(es) that replies go to, when not the sender.") Object replyTo,
            @Input(name = "exclude_sender", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Leave the original sender out and reply only to the other recipients.") boolean excludeSender,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"", description = D_SESSION) String session) {
        return replyImpl(ctx, id, body, html, true, include, attachments, host, port, security, user, password, token, from, folder, cc, bcc, importance, replyTo, excludeSender, session);
    }

    @Action(id = "delete_all", name = "Delete all matching emails", category = "Mailbox", timeoutSeconds = 600, capabilities = "network",
            description = "Delete every email in a folder that matches the filters (all emails when no filter is set).",
            panel = "folder, unread_only, read_only, subject_contains, from_contains, since, before | permanent, expunge, to_contains, body_contains, session")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of emails deleted.")
    public Map<String, Object> deleteAll(ActionContext ctx,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"INBOX\"", description = "Mail folder.") String folder,
            @Input(name = "unread_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only unread emails.") boolean unread,
            @Input(name = "from_contains", type = OoaType.STRING, required = false, description = "Sender contains this text; separate several with ; to match any of them.") String from,
            @Input(name = "subject_contains", type = OoaType.STRING, required = false, description = "Subject contains this text; separate several with ; to match any of them.") String subject,
            @Input(name = "since", type = OoaType.DATETIME, required = false, description = "Received on or after this date.") LocalDateTime since,
            @Input(name = "before", type = OoaType.DATETIME, required = false, description = "Received before this date.") LocalDateTime before,
            @Input(name = "permanent", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Delete for good instead of moving to Trash or Deleted Items.") boolean permanent,
            @Input(name = "expunge", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Permanent mode: remove the emails now; when false they are only marked deleted until the session closes.") boolean expunge,
            @Input(name = "read_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only emails already read.") boolean readOnly,
            @Input(name = "to_contains", type = OoaType.STRING, required = false, description = "A To recipient contains this text.") String toText,
            @Input(name = "body_contains", type = OoaType.STRING, required = false, description = "The body contains this text (searched by the server).") String bodyText,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"", description = D_SESSION) String session) {
        try {
            MailSession s = ms(ctx, session);
            Folder src = s.folder(folder);
            Message[] msgs = matching(src, filter(unread, readOnly, from, subject, since, before, toText, bodyText, s.pop3));
            if (msgs.length == 0) return Map.of("count", BigDecimal.ZERO);
            if (!permanent && !s.pop3) for (String trash : List.of("Deleted Items", "Trash", "[Gmail]/Trash")) {
                Folder t = s.store.getFolder(trash);
                if (t.exists() && !trash.equalsIgnoreCase(folder)) { src.copyMessages(msgs, t); expunge = true; break; }
            }
            src.setFlags(msgs, new Flags(Flags.Flag.DELETED), true);
            if (expunge) expunge(src);
            ctx.info("Deleted " + msgs.length + " email(s) from " + folder);
            return Map.of("count", BigDecimal.valueOf(msgs.length));
        } catch (MessagingException e) { throw mail(e); }
    }

    @Action(id = "move_all", name = "Move all matching emails", category = "Mailbox", timeoutSeconds = 600, capabilities = "network",
            description = "Move every email in a folder that matches the filters to another folder.",
            panel = "to_folder, unread_only, read_only, folder, subject_contains, from_contains, since, before | to_contains, body_contains, create_folder, session")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of emails moved.")
    public Map<String, Object> moveAll(ActionContext ctx,
            @Input(name = "to_folder", type = OoaType.STRING, description = "Destination folder.") String to,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"INBOX\"", description = "Current folder.") String folder,
            @Input(name = "unread_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only unread emails.") boolean unread,
            @Input(name = "from_contains", type = OoaType.STRING, required = false, description = "Sender contains this text; separate several with ; to match any of them.") String from,
            @Input(name = "subject_contains", type = OoaType.STRING, required = false, description = "Subject contains this text; separate several with ; to match any of them.") String subject,
            @Input(name = "since", type = OoaType.DATETIME, required = false, description = "Received on or after this date.") LocalDateTime since,
            @Input(name = "before", type = OoaType.DATETIME, required = false, description = "Received before this date.") LocalDateTime before,
            @Input(name = "read_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only emails already read.") boolean readOnly,
            @Input(name = "to_contains", type = OoaType.STRING, required = false, description = "A To recipient contains this text.") String toText,
            @Input(name = "body_contains", type = OoaType.STRING, required = false, description = "The body contains this text (searched by the server).") String bodyText,
            @Input(name = "create_folder", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Create the destination folder when it does not exist.") boolean create,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"", description = D_SESSION) String session) {
        try {
            MailSession s = ms(ctx, session);
            imapOnly(s, "Move all");
            Folder src = s.folder(folder);
            Folder dst = destination(s, to, create);
            Message[] msgs = matching(src, filter(unread, readOnly, from, subject, since, before, toText, bodyText, false));
            if (msgs.length == 0) return Map.of("count", BigDecimal.ZERO);
            src.copyMessages(msgs, dst);
            src.setFlags(msgs, new Flags(Flags.Flag.DELETED), true);
            src.expunge();
            ctx.info("Moved " + msgs.length + " email(s) from " + folder + " to " + to);
            return Map.of("count", BigDecimal.valueOf(msgs.length));
        } catch (MessagingException e) { throw mail(e); }
    }

    @Action(id = "folder_exists", name = "Check folder exists", category = "Mailbox", idempotent = true, capabilities = "network",
            description = "Check whether a mail folder exists in the open mailbox.",
            panel = "folder | session")
    @Output(name = "exists", type = OoaType.BOOLEAN, description = "True when the folder exists.")
    public Map<String, Object> folderExists(ActionContext ctx,
            @Input(name = "folder", type = OoaType.STRING, example = "Processed", description = "Folder name; use / or the server's separator for subfolders.") String folder,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"", description = D_SESSION) String session) {
        if (folder == null || folder.isBlank()) throw new ActionException("EMAIL.BAD_INPUT", "Give a folder name", null);
        try { return Map.of("exists", ms(ctx, session).store.getFolder(folder).exists()); }
        catch (MessagingException e) { throw mail(e); }
    }

    @Action(id = "save_email_as_eml", name = "Save email as .eml", category = "Mailbox", capabilities = {"network", "filesystem"},
            description = "Save a complete email, with attachments, as an .eml file that mail programs can open.",
            panel = "id, folder, local_path, overwrite | session")
    @Output(name = "path", type = OoaType.FILE, description = "Saved local path.")
    public Map<String, Object> saveEml(ActionContext ctx,
            @Input(name = "id", type = OoaType.NUMBER, description = "Email id from Find emails.") long id,
            @Input(name = "local_path", type = OoaType.FILE, example = "mail/invoice.eml", description = "Where to save the .eml file; a path ending in / or \\ is a folder, and the file is named after the subject.") String local,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"INBOX\"", description = "Mail folder.") String folder,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace an existing file; when false a number such as (2) is added to the name.") boolean overwrite,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"", description = D_SESSION) String session) {
        try {
            Message m = byUid(ms(ctx, session).folder(folder), id);
            return Map.of("path", writeEml(m, emlTarget(ctx.resolvePath(local), local.endsWith("/") || local.endsWith("\\"), m.getSubject(), overwrite)).toString());
        }
        catch (MessagingException e) { throw mail(e);
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true); }
    }

}
