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
        final Map<String, Folder> open = new HashMap<>();
        MailSession(Store s) { store = s; }
        Folder folder(String name) throws MessagingException {
            Folder f = open.get(name);
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

    private static ActionException mail(MessagingException e) {
        String m = String.valueOf(e.getMessage());
        if (e instanceof AuthenticationFailedException) return new ActionException("EMAIL.AUTH_FAILED", "Mail sign-in failed: " + m,
                "Check the user and password or token; Microsoft 365 and Gmail need OAuth or an app password.");
        if (m.contains("connect") || m.contains("timed out") || m.contains("UnknownHost"))
            return new ActionException("EMAIL.UNREACHABLE", "Cannot reach the mail server: " + m, "Check host, port, security and network access.", true);
        return new ActionException("EMAIL.ERROR", m, null);
    }

    private static Properties props(String protocol, String host, int port, String security, boolean oauth) {
        Properties p = new Properties();
        String pre = "mail." + protocol + ".";
        p.put(pre + "host", host);
        p.put(pre + "port", String.valueOf(port));
        p.put(pre + "connectiontimeout", "15000");
        p.put(pre + "timeout", "60000");
        p.put(pre + "writetimeout", "60000");
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
            description = "Send an email (plain text or HTML) with optional CC, BCC and attachments through an SMTP server.")
    @Output(name = "message_id", type = OoaType.STRING, description = "Message-ID of the sent email.")
    public Map<String, Object> send(ActionContext ctx,
            @Input(name = "smtp_host", type = OoaType.STRING, example = "smtp.office365.com", description = "SMTP server.") String host,
            @Input(name = "port", type = OoaType.NUMBER, required = false, defaultValue = "587", description = "587 for STARTTLS, 465 for SSL.") int port,
            @Input(name = "security", type = OoaType.STRING, required = false, defaultValue = "\"starttls\"", enumValues = {"starttls", "ssl", "none"}, description = "Connection security.") String security,
            @Input(name = "user", type = OoaType.STRING, description = "Mailbox user name.") String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password or app password from the vault.") Object password,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = "OAuth 2.0 access token (Microsoft 365, Gmail) from the vault.") Object token,
            @Input(name = "from", type = OoaType.STRING, required = false, description = "Sender address; the user when empty.") String from,
            @Input(name = "to", description = "Recipient address(es): text separated by , or ; or a List.") Object to,
            @Input(name = "cc", required = false, description = "CC address(es).") Object cc,
            @Input(name = "bcc", required = false, description = "BCC address(es).") Object bcc,
            @Input(name = "subject", type = OoaType.STRING, description = "Subject.") String subject,
            @Input(name = "body", type = OoaType.STRING, required = false, defaultValue = "\"\"", description = "Message body.") String body,
            @Input(name = "html", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Body is HTML.") boolean html,
            @Input(name = "attachments", type = OoaType.LIST, required = false, description = "File paths to attach.") List<Object> attachments) {
        boolean oauth = token != null;
        Session session = Session.getInstance(props("smtp", host, port, security, oauth));
        try {
            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(from != null && !from.isBlank() ? from : user, true));
            msg.setRecipients(Message.RecipientType.TO, addresses(to));
            if (cc != null) msg.setRecipients(Message.RecipientType.CC, addresses(cc));
            if (bcc != null) msg.setRecipients(Message.RecipientType.BCC, addresses(bcc));
            msg.setSubject(subject, "UTF-8");
            MimeMultipart mp = new MimeMultipart();
            MimeBodyPart text = new MimeBodyPart();
            text.setText(body, "UTF-8", html ? "html" : "plain");
            mp.addBodyPart(text);
            if (attachments != null) for (Object a : attachments) {
                Path p = ctx.resolvePath(Values.text(a));
                if (!Files.isRegularFile(p)) throw new ActionException("FILE.NOT_FOUND", "Attachment not found: " + p, "Check the attachment path.");
                MimeBodyPart part = new MimeBodyPart();
                part.attachFile(p.toFile());
                mp.addBodyPart(part);
            }
            msg.setContent(mp);
            msg.setSentDate(new Date());
            try (Transport t = session.getTransport("smtp")) {
                t.connect(host, port, user, secret(password, token));
                msg.saveChanges();
                t.sendMessage(msg, msg.getAllRecipients());
            }
            ctx.info("Sent '" + subject + "' to " + Arrays.toString(msg.getRecipients(Message.RecipientType.TO)));
            return Map.of("message_id", Objects.toString(msg.getMessageID(), ""));
        } catch (AddressException e) {
            throw new ActionException("EMAIL.BAD_ADDRESS", "Invalid email address: " + e.getMessage(), "Check the recipient addresses.");
        } catch (MessagingException e) { throw mail(e);
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true); }
    }

    @Action(id = "connect", name = "Connect to mailbox", category = "Mailbox", capabilities = "network", description = "Sign in to a mailbox over IMAP as a named session.")
    public Map<String, Object> connect(ActionContext ctx,
            @Input(name = "imap_host", type = OoaType.STRING, example = "outlook.office365.com", description = "IMAP server.") String host,
            @Input(name = "port", type = OoaType.NUMBER, required = false, defaultValue = "993", description = "IMAP port (993 for SSL).") int port,
            @Input(name = "user", type = OoaType.STRING, description = "Mailbox user name.") String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password or app password from the vault.") Object password,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, required = false, description = "OAuth 2.0 access token from the vault.") Object token,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"", description = "Session name.") String session) {
        try {
            Session s = Session.getInstance(props("imaps", host, port, "ssl", token != null));
            Store store = s.getStore("imaps");
            store.connect(host, port, user, secret(password, token));
            ctx.sessions().put(session, new MailSession(store));
        } catch (MessagingException e) { throw mail(e); }
        return Map.of();
    }

    private static MailSession ms(ActionContext ctx, String name) { return ctx.session(name, MailSession.class); }

    private static Message byUid(Folder f, long uid) throws MessagingException {
        Message m = ((UIDFolder) f).getMessageByUID(uid);
        if (m == null) throw new ActionException("EMAIL.NOT_FOUND", "No email with id " + uid + " in " + f.getFullName(), "The email may have been moved or deleted.");
        return m;
    }

    private static String fromText(Message m) throws MessagingException {
        Address[] a = m.getFrom();
        return a == null || a.length == 0 ? "" : (a[0] instanceof InternetAddress ia ? ia.getAddress() : a[0].toString());
    }

    @Action(id = "list", name = "Find emails", category = "Mailbox", idempotent = true, timeoutSeconds = 300, capabilities = "network",
            description = "List emails in a folder, newest first, filtered by unread, sender, subject and date; loop over the table to process them.")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, from, subject, received, unread, has_attachments.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of emails.")
    public Map<String, Object> list(ActionContext ctx,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"INBOX\"", description = "Mail folder.") String folder,
            @Input(name = "unread_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only unread emails.") boolean unread,
            @Input(name = "from_contains", type = OoaType.STRING, required = false, description = "Sender contains this text.") String from,
            @Input(name = "subject_contains", type = OoaType.STRING, required = false, description = "Subject contains this text.") String subject,
            @Input(name = "since", type = OoaType.DATETIME, required = false, description = "Received on or after this date.") LocalDateTime since,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "100", description = "Maximum emails returned.") int max,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"", description = "Session name.") String session) {
        try {
            Folder f = ms(ctx, session).folder(folder);
            List<SearchTerm> terms = new ArrayList<>();
            if (unread) terms.add(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
            if (from != null && !from.isBlank()) terms.add(new FromStringTerm(from));
            if (subject != null && !subject.isBlank()) terms.add(new SubjectTerm(subject));
            if (since != null) terms.add(new ReceivedDateTerm(ComparisonTerm.GE, Date.from(since.toInstant(ZoneOffset.UTC))));
            Message[] msgs = terms.isEmpty() ? f.getMessages() : f.search(terms.size() == 1 ? terms.get(0) : new AndTerm(terms.toArray(new SearchTerm[0])));
            FetchProfile fp = new FetchProfile();
            fp.add(FetchProfile.Item.ENVELOPE); fp.add(FetchProfile.Item.FLAGS); fp.add(FetchProfile.Item.CONTENT_INFO); fp.add(UIDFolder.FetchProfileItem.UID);
            f.fetch(msgs, fp);
            List<Object> rows = new ArrayList<>();
            for (int i = msgs.length - 1; i >= 0 && rows.size() < max; i--) {
                Message m = msgs[i];
                Date d = m.getReceivedDate() != null ? m.getReceivedDate() : m.getSentDate();
                rows.add(new ArrayList<>(Arrays.asList(BigDecimal.valueOf(((UIDFolder) f).getUID(m)), fromText(m), Objects.toString(m.getSubject(), ""),
                        d == null ? null : LocalDateTime.ofInstant(d.toInstant(), ZoneOffset.UTC), !m.isSet(Flags.Flag.SEEN), m.isMimeType("multipart/mixed"))));
            }
            return Map.of("table", Values.table(List.of("id", "from", "subject", "received", "unread", "has_attachments"), rows), "count", BigDecimal.valueOf(rows.size()));
        } catch (MessagingException e) { throw mail(e); }
    }

    private static void collect(Part p, StringBuilder text, StringBuilder html, List<Part> attachments) throws MessagingException, IOException {
        String disp = p.getDisposition();
        if (p.getFileName() != null && (Part.ATTACHMENT.equalsIgnoreCase(disp) || Part.INLINE.equalsIgnoreCase(disp) || !p.isMimeType("text/*"))) { attachments.add(p); return; }
        if (p.isMimeType("text/plain")) text.append(p.getContent());
        else if (p.isMimeType("text/html")) html.append(p.getContent());
        else if (p.isMimeType("multipart/*")) { Multipart mp = (Multipart) p.getContent(); for (int i = 0; i < mp.getCount(); i++) collect(mp.getBodyPart(i), text, html, attachments); }
    }

    @Action(id = "get", name = "Read email", category = "Mailbox", idempotent = true, capabilities = "network", description = "Read one email: sender, recipients, subject, text, HTML and attachment names.")
    @Output(name = "email", type = OoaType.DICTIONARY, description = "id, from, to, cc, subject, received, text, html, attachments.")
    public Map<String, Object> get(ActionContext ctx,
            @Input(name = "id", type = OoaType.NUMBER, description = "Email id from Find emails.") long id,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"INBOX\"", description = "Mail folder.") String folder,
            @Input(name = "mark_read", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Mark the email as read.") boolean markRead,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"", description = "Session name.") String session) {
        try {
            Message m = byUid(ms(ctx, session).folder(folder), id);
            StringBuilder text = new StringBuilder(), html = new StringBuilder();
            List<Part> atts = new ArrayList<>();
            collect(m, text, html, atts);
            List<Object> names = new ArrayList<>();
            for (Part a : atts) names.add(MimeUtility.decodeText(a.getFileName()));
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", BigDecimal.valueOf(id));
            e.put("from", fromText(m));
            e.put("to", m.getRecipients(Message.RecipientType.TO) == null ? List.of() : Arrays.stream(m.getRecipients(Message.RecipientType.TO)).map(Address::toString).toList());
            e.put("cc", m.getRecipients(Message.RecipientType.CC) == null ? List.of() : Arrays.stream(m.getRecipients(Message.RecipientType.CC)).map(Address::toString).toList());
            e.put("subject", Objects.toString(m.getSubject(), ""));
            Date d = m.getReceivedDate();
            e.put("received", d == null ? null : LocalDateTime.ofInstant(d.toInstant(), ZoneOffset.UTC));
            e.put("text", text.toString());
            e.put("html", html.toString());
            e.put("attachments", names);
            if (markRead) m.setFlag(Flags.Flag.SEEN, true);
            return Map.of("email", e);
        } catch (MessagingException | IOException e) { throw e instanceof MessagingException me ? mail(me) : new ActionException("EMAIL.READ_FAILED", e.getMessage()); }
    }

    /** Attachment names come from outside: keep only a safe file name, never a path. */
    static String safeName(String name) {
        String n = Path.of(name.replace('\\', '/')).getFileName().toString().replaceAll("[\\x00-\\x1f<>:\"/\\\\|?*]", "_").strip();
        if (n.isEmpty() || n.equals(".") || n.equals("..")) n = "attachment";
        return n.length() > 150 ? n.substring(n.length() - 150) : n;
    }

    @Action(id = "save_attachments", name = "Save attachments", category = "Mailbox", capabilities = {"network", "filesystem"},
            description = "Save an email's attachments to a folder, optionally only names matching a pattern such as *.pdf.")
    @Output(name = "files", type = OoaType.LIST, description = "Saved file paths.")
    public Map<String, Object> saveAttachments(ActionContext ctx,
            @Input(name = "id", type = OoaType.NUMBER, description = "Email id.") long id,
            @Input(name = "target_folder", type = OoaType.FILE, description = "Folder to save into.") String target,
            @Input(name = "name_pattern", type = OoaType.STRING, required = false, defaultValue = "\"*\"", description = "Only names matching this pattern, e.g. *.pdf.") String pattern,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"INBOX\"", description = "Mail folder.") String folder,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"", description = "Session name.") String session) {
        try {
            Message m = byUid(ms(ctx, session).folder(folder), id);
            List<Part> atts = new ArrayList<>();
            collect(m, new StringBuilder(), new StringBuilder(), atts);
            Path dir = ctx.resolvePath(target);
            Files.createDirectories(dir);
            PathMatcher pm = FileSystems.getDefault().getPathMatcher("glob:" + pattern.toLowerCase(Locale.ROOT));
            List<Object> saved = new ArrayList<>();
            for (Part a : atts) {
                String name = safeName(MimeUtility.decodeText(a.getFileName()));
                if (!pm.matches(Path.of(name.toLowerCase(Locale.ROOT)))) continue;
                Path out = dir.resolve(name);
                for (int i = 2; Files.exists(out); i++) out = dir.resolve(name.replaceFirst("(\\.[^.]+)?$", " (" + i + ")$1"));
                ((MimeBodyPart) a).saveFile(out.toFile());
                saved.add(out.toString());
            }
            return Map.of("files", saved);
        } catch (MessagingException e) { throw mail(e);
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true); }
    }

    @Action(id = "mark", name = "Mark read or unread", category = "Mailbox", capabilities = "network", description = "Mark an email as read or unread.")
    public Map<String, Object> mark(ActionContext ctx,
            @Input(name = "id", type = OoaType.NUMBER, description = "Email id.") long id,
            @Input(name = "read", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "True = read, false = unread.") boolean read,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"INBOX\"", description = "Mail folder.") String folder,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"", description = "Session name.") String session) {
        try { byUid(ms(ctx, session).folder(folder), id).setFlag(Flags.Flag.SEEN, read); } catch (MessagingException e) { throw mail(e); }
        return Map.of();
    }

    @Action(id = "move", name = "Move email", category = "Mailbox", capabilities = "network", description = "Move an email to another folder, e.g. Processed.")
    public Map<String, Object> move(ActionContext ctx,
            @Input(name = "id", type = OoaType.NUMBER, description = "Email id.") long id,
            @Input(name = "to_folder", type = OoaType.STRING, description = "Destination folder.") String to,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"INBOX\"", description = "Current folder.") String folder,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"", description = "Session name.") String session) {
        try {
            MailSession s = ms(ctx, session);
            Folder src = s.folder(folder);
            Message m = byUid(src, id);
            Folder dst = s.store.getFolder(to);
            if (!dst.exists()) throw new ActionException("EMAIL.NO_FOLDER", "Mail folder '" + to + "' not found", "Create it in the mailbox first.");
            src.copyMessages(new Message[]{m}, dst);
            m.setFlag(Flags.Flag.DELETED, true);
            src.expunge();
        } catch (MessagingException e) { throw mail(e); }
        return Map.of();
    }

    @Action(id = "delete", name = "Delete email", category = "Mailbox", capabilities = "network", description = "Delete an email (moved to Trash/Deleted Items when that folder exists).")
    public Map<String, Object> delete(ActionContext ctx,
            @Input(name = "id", type = OoaType.NUMBER, description = "Email id.") long id,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"INBOX\"", description = "Mail folder.") String folder,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"", description = "Session name.") String session) {
        try {
            MailSession s = ms(ctx, session);
            for (String trash : List.of("Deleted Items", "Trash", "[Gmail]/Trash")) {
                Folder t = s.store.getFolder(trash);
                if (t.exists() && !trash.equalsIgnoreCase(folder)) return move(ctx, id, trash, folder, session);
            }
            Folder src = s.folder(folder);
            byUid(src, id).setFlag(Flags.Flag.DELETED, true);
            src.expunge();
        } catch (MessagingException e) { throw mail(e); }
        return Map.of();
    }

    @Action(id = "get_folders", name = "Get folders", category = "Mailbox", idempotent = true, capabilities = "network", description = "List all mail folders.")
    @Output(name = "folders", type = OoaType.LIST, description = "Folder names.")
    public Map<String, Object> getFolders(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"", description = "Session name.") String session) {
        try {
            List<Object> out = new ArrayList<>();
            for (Folder f : ms(ctx, session).store.getDefaultFolder().list("*")) out.add(f.getFullName());
            return Map.of("folders", out);
        } catch (MessagingException e) { throw mail(e); }
    }

    @Action(id = "disconnect", name = "Disconnect", category = "Mailbox", description = "Close the mailbox session.")
    public Map<String, Object> disconnect(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"mail\"", description = "Session name.") String session) {
        Object o = ctx.sessions().remove(session);
        if (o instanceof MailSession m) try { m.close(); } catch (MessagingException ignored) { }
        return Map.of();
    }

}
