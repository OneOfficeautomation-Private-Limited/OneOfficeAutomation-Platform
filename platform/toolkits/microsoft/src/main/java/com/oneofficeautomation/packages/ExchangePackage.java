package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.net.ssl.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Mail on an on-premises Exchange Server (2013, 2016, 2019 and Subscription Edition) through Exchange Web Services:
 * SOAP over HTTPS, written here without a client library. Sign-in is NTLM (Java's HttpURLConnection answers the
 * challenge, as REST's windows_auth does) or Basic, with the password from the vault. Microsoft is retiring EWS for
 * Exchange Online, so Microsoft 365 mailboxes use the Microsoft 365 toolkit (Graph) instead.
 *
 * <p>Actions and outputs follow the Email toolkit (IMAP), so a bot can switch between them: the same action ids, the
 * same Find emails columns and the same email record. Email ids are EWS item ids (text), which change when an email is
 * moved; Move email returns the new one.
 */
@OoaPackage(id = "ooa.exchange", name = "Exchange on-premises (EWS)", version = "1.0.0",
        description = "Read, send, reply to, move, mark and delete emails in an on-premises Exchange Server 2013-2019 or SE mailbox through Exchange Web Services (EWS). "
                + "For Exchange Online (Microsoft 365) use the Microsoft 365 toolkit, as Microsoft is retiring EWS there.")
public final class ExchangePackage {
    static final String P = "EXCHANGE";
    static final String NS_SOAP = "http://schemas.xmlsoap.org/soap/envelope/";
    static final String NS_TYPES = "http://schemas.microsoft.com/exchange/services/2006/types";
    static final String NS_MESSAGES = "http://schemas.microsoft.com/exchange/services/2006/messages";
    private static final String D_SESSION = "Session name of the open Exchange mailbox.";
    private static final String D_RESPONSE_FILES = "Files to attach (paths); the reply is then saved as a draft, given the files and sent.";
    private static final String D_ID = "Email id from Find emails (an EWS item id).";
    private static final String D_FOLDER = "Folder: a well-known name (inbox, sentitems, deleteditems, drafts, junkemail, archive), a path such as Inbox/Invoices, or a folder id.";

    static {
        // as for REST's windows_auth: NTLM first when the server offers several schemes, since Kerberos needs a krb5 set-up
        if (System.getProperty("http.auth.preference") == null) System.setProperty("http.auth.preference", "NTLM");
    }

    /** Well-known folder names EWS knows by itself, with the display names people type for them. */
    static final Map<String, String> WELL_KNOWN = Map.ofEntries(
            Map.entry("inbox", "inbox"), Map.entry("sentitems", "sentitems"), Map.entry("sent items", "sentitems"), Map.entry("sent", "sentitems"),
            Map.entry("deleteditems", "deleteditems"), Map.entry("deleted items", "deleteditems"), Map.entry("drafts", "drafts"),
            Map.entry("junkemail", "junkemail"), Map.entry("junk email", "junkemail"), Map.entry("junk", "junkemail"), Map.entry("outbox", "outbox"),
            Map.entry("archive", "archivemsgfolderroot"), Map.entry("msgfolderroot", "msgfolderroot"));

    /** An open mailbox: where EWS is, how to sign in, and which mailbox to work in. The password stays a SecureString. */
    static final class ExchangeSession {
        final String url, user, signIn, mailbox;
        final SecureString password;
        final boolean impersonate, insecure;
        final int timeoutSeconds;
        /** One per session, so Java keeps the NTLM sign-in of a kept-alive connection instead of starting again each call. */
        final Authenticator authenticator;
        final Map<String, String> folderIds = new HashMap<>();

        ExchangeSession(String url, String user, SecureString password, String signIn, String mailbox, boolean impersonate, boolean insecure, int timeoutSeconds) {
            this.url = url; this.user = user; this.password = password; this.signIn = signIn; this.mailbox = mailbox;
            this.impersonate = impersonate; this.insecure = insecure; this.timeoutSeconds = timeoutSeconds;
            this.authenticator = new Authenticator() {
                @Override protected PasswordAuthentication getPasswordAuthentication() {
                    return "ntlm".equals(signIn) && getRequestorType() == RequestorType.SERVER ? new PasswordAuthentication(user, password.reveal().toCharArray()) : null;
                }
            };
        }

        boolean otherMailbox() { return mailbox != null && !mailbox.isBlank(); }

        /** A well-known folder of the session's mailbox (the Mailbox element opens another person's or a shared mailbox as a delegate). */
        String distinguished(String id) {
            return "<t:DistinguishedFolderId Id=\"" + id + "\">" + (otherMailbox() && !impersonate ? "<t:Mailbox><t:EmailAddress>" + x(mailbox) + "</t:EmailAddress></t:Mailbox>" : "")
                    + "</t:DistinguishedFolderId>";
        }
    }

    // ------------------------------------------------------------------ connect

    @Action(id = "connect", name = "Connect to Exchange mailbox", category = "Mailbox", capabilities = "network",
            description = "Sign in to an on-premises Exchange Server mailbox through Exchange Web Services (EWS) as a named session; checks the sign-in by opening the inbox. For Exchange Online (Microsoft 365) use the Microsoft 365 toolkit instead: Microsoft is retiring EWS there.",
            panel = "ews_url, user, password, sign_in, [Open another person's or a shared mailbox: mailbox, impersonate] | timeout_seconds, insecure_tls, session")
    public Map<String, Object> connect(ActionContext ctx,
            @Input(name = "ews_url", type = OoaType.STRING, label = "Exchange web services address (URL)", example = "https://mail.example.com/EWS/Exchange.asmx",
                    description = "Address of the Exchange server's web services, usually https://<mail server>/EWS/Exchange.asmx.") String url,
            @Input(name = "user", type = OoaType.STRING, example = "ACME\\svc-mailbot", description = "User that signs in: DOMAIN\\user or user@domain.") String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, description = "The user's password from the vault (a Credential).") Object password,
            @Input(name = "sign_in", type = OoaType.STRING, required = false, defaultValue = "\"ntlm\"", enumValues = {"ntlm", "basic"},
                    description = "ntlm: Windows sign-in, which Exchange accepts by default; basic: user name and password in each request, only over https and where the server allows it.") String signIn,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, example = "invoices@acme.com",
                    description = "Work in this mailbox instead of the user's own, e.g. a shared mailbox; the user needs full access to it, or the impersonation role with impersonate.") String mailbox,
            @Input(name = "impersonate", type = OoaType.BOOLEAN, required = false, defaultValue = "false", visibleWhen = "mailbox!=",
                    description = "Act as the mailbox's owner through Exchange impersonation (the user needs the ApplicationImpersonation role) instead of opening it as a delegate.") boolean impersonate,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "60", min = 1, max = 600, description = "How long to wait for each answer from the server.") int timeout,
            @Input(name = "insecure_tls", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    description = "Accept any server certificate (self-signed or expired); for test systems only.") boolean insecure,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"exchange\"", description = "Session name.") String session) {
        String u = url == null ? "" : url.strip();
        try {
            URI uri = new URI(u);
            if (uri.getHost() == null || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) throw new URISyntaxException(u, "no host");
        } catch (URISyntaxException e) {
            throw new ActionException(P + ".BAD_URL", "Invalid EWS address '" + u + "'", "Use the server's EWS address, e.g. https://mail.example.com/EWS/Exchange.asmx.");
        }
        if (user == null || user.isBlank()) throw new ActionException(P + ".NO_USER", "Give the user that signs in to Exchange", "Fill in user as DOMAIN\\user or user@domain.");
        if (!(password instanceof SecureString pw))
            throw new ActionException(P + ".NO_SECRET", "Input 'password' must come from the vault (a Credential), so nothing was sent",
                    "Give a vault reference {\"$credential\": \"locker/credential/attribute\"} or a Credential variable.");
        String mode = signIn == null || signIn.isBlank() ? "ntlm" : signIn.strip().toLowerCase(Locale.ROOT);
        if (!mode.equals("ntlm") && !mode.equals("basic")) throw new ActionException(P + ".BAD_INPUT", "Unknown sign_in '" + signIn + "'", "Use ntlm or basic.");
        if (impersonate && (mailbox == null || mailbox.isBlank()))
            throw new ActionException(P + ".BAD_INPUT", "impersonate needs the mailbox to act as", "Fill in mailbox, or clear impersonate.");
        if (insecure) ctx.warn("Server certificates are not checked for this Exchange session; use this only with test systems.");
        if (mode.equals("basic") && u.toLowerCase(Locale.ROOT).startsWith("http:"))
            ctx.warn("Basic sign-in over http sends the password readable on the network; use an https address.");
        ExchangeSession s = new ExchangeSession(u, user.strip(), pw, mode, mailbox == null || mailbox.isBlank() ? null : mailbox.strip(), impersonate, insecure, Math.max(1, timeout));
        // opening the inbox proves the address, the sign-in and the access to the mailbox in one call
        call(ctx, s, "GetFolder", "<m:GetFolder><m:FolderShape><t:BaseShape>IdOnly</t:BaseShape></m:FolderShape><m:FolderIds>" + s.distinguished("inbox") + "</m:FolderIds></m:GetFolder>");
        ctx.sessions().put(session == null || session.isBlank() ? "exchange" : session, s);
        return Map.of();
    }

    private static ExchangeSession es(ActionContext ctx, String name) { return ctx.sessionOrOnly(name, "exchange", ExchangeSession.class); }

    @Action(id = "disconnect", name = "Disconnect", category = "Mailbox", description = "Close the Exchange mailbox session.",
            panel = "session")
    public Map<String, Object> disconnect(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"exchange\"", description = "Session name.") String session) {
        ctx.sessions().remove(session == null || session.isBlank() ? "exchange" : session);
        return Map.of();
    }

    // ------------------------------------------------------------------ find and read

    static final List<String> SUMMARY_COLUMNS = List.of("id", "from", "subject", "received", "unread", "has_attachments");

    @Action(id = "list", name = "Find emails", category = "Mailbox", idempotent = true, timeoutSeconds = 300, capabilities = "network",
            description = "List emails in an Exchange folder, newest first, filtered by unread, sender, subject and date; loop over the table to process them.",
            panel = "folder, unread_only, read_only, from_contains, subject_contains, since, before, max_results | body_contains, has_attachments_only, oldest_first, session")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, from, subject, received, unread, has_attachments.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of emails.")
    public Map<String, Object> list(ActionContext ctx,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"inbox\"", description = D_FOLDER) String folder,
            @Input(name = "unread_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only unread emails.") boolean unread,
            @Input(name = "from_contains", type = OoaType.STRING, required = false, description = "Sender contains this text; separate several with ; to match any of them.") String from,
            @Input(name = "subject_contains", type = OoaType.STRING, required = false, description = "Subject contains this text; separate several with ; to match any of them.") String subject,
            @Input(name = "since", type = OoaType.DATETIME, required = false, description = "Received on or after this time (UTC).") LocalDateTime since,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "100", min = 1, description = "Maximum emails returned.") int max,
            @Input(name = "before", type = OoaType.DATETIME, required = false, description = "Received before this time (UTC).") LocalDateTime before,
            @Input(name = "read_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only emails already read.") boolean readOnly,
            @Input(name = "has_attachments_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only emails that have attachments.") boolean withAtts,
            @Input(name = "oldest_first", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Return the oldest emails first instead of the newest.") boolean oldestFirst,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"exchange\"", description = D_SESSION) String session,
            @Input(name = "body_contains", type = OoaType.STRING, required = false, description = "Body contains this text (searched by the server); separate several with ; to match any of them.") String bodyContains) {
        List<Object> rows = new ArrayList<>();
        for (Map<String, Object> e : find(ctx, es(ctx, session), folder, unread, readOnly, from, subject, since, before, withAtts, oldestFirst, max, bodyContains))
            rows.add(new ArrayList<>(e.values()));
        return Map.of("table", Values.table(SUMMARY_COLUMNS, rows), "count", BigDecimal.valueOf(rows.size()));
    }

    @LoopIterator(id = "emails", name = "Each email in an Exchange folder",
            description = "Yield each email matching the Find emails filters as a dictionary: id, from, subject, received, unread, has_attachments.")
    public Iterable<Object> emails(ActionContext ctx,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"inbox\"", description = D_FOLDER) String folder,
            @Input(name = "unread_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only unread emails.") boolean unread,
            @Input(name = "from_contains", type = OoaType.STRING, required = false, description = "Sender contains this text; separate several with ; to match any of them.") String from,
            @Input(name = "subject_contains", type = OoaType.STRING, required = false, description = "Subject contains this text; separate several with ; to match any of them.") String subject,
            @Input(name = "since", type = OoaType.DATETIME, required = false, description = "Received on or after this time (UTC).") LocalDateTime since,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "100", min = 1, description = "Stop after this many emails.") int max,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"exchange\"", description = D_SESSION) String session,
            @Input(name = "before", type = OoaType.DATETIME, required = false, description = "Received before this time (UTC).") LocalDateTime before,
            @Input(name = "read_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only emails already read.") boolean readOnly,
            @Input(name = "has_attachments_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only emails that have attachments.") boolean withAtts,
            @Input(name = "oldest_first", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "The oldest emails first instead of the newest.") boolean oldestFirst,
            @Input(name = "body_contains", type = OoaType.STRING, required = false, description = "Body contains this text (searched by the server); separate several with ; to match any of them.") String bodyContains,
            @Input(name = "with_body", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = MailRecord.D_WITH_BODY) boolean withBody) {
        // the list is taken before the loop starts, so emails can be moved or deleted inside it
        List<Map<String, Object>> found = find(ctx, es(ctx, session), folder, unread, readOnly, from, subject, since, before, withAtts, oldestFirst, max, bodyContains);
        return MailRecord.each(found, withBody, row -> (Map<String, Object>) get(ctx, String.valueOf(row.get("id")), false, session).get("email"));
    }

    /** Earlier signatures, kept for callers written before body_contains and the loop's extra filters (A6). */
    public Map<String, Object> list(ActionContext ctx, String folder, boolean unread, String from, String subject, LocalDateTime since, int max, LocalDateTime before,
                                    boolean readOnly, boolean withAtts, boolean oldestFirst, String session) {
        return list(ctx, folder, unread, from, subject, since, max, before, readOnly, withAtts, oldestFirst, session, null);
    }

    public Iterable<Object> emails(ActionContext ctx, String folder, boolean unread, String from, String subject, LocalDateTime since, int max, String session) {
        return emails(ctx, folder, unread, from, subject, since, max, session, null, false, false, false, null, false);
    }

    /** The emails matching the filters, each keyed by SUMMARY_COLUMNS. Subject, dates and flags are filtered by the server; the sender here. */
    static List<Map<String, Object>> find(ActionContext ctx, ExchangeSession s, String folder, boolean unread, boolean readOnly, String from, String subject,
                                          LocalDateTime since, LocalDateTime before, boolean withAtts, boolean oldestFirst, int max) {
        return find(ctx, s, folder, unread, readOnly, from, subject, since, before, withAtts, oldestFirst, max, null);
    }

    static List<Map<String, Object>> find(ActionContext ctx, ExchangeSession s, String folder, boolean unread, boolean readOnly, String from, String subject,
                                          LocalDateTime since, LocalDateTime before, boolean withAtts, boolean oldestFirst, int max, String bodyContains) {
        if (unread && readOnly) throw new ActionException(P + ".BAD_INPUT", "unread_only and read_only cannot both be true", "Choose one of them.");
        int limit = Math.max(1, max);
        List<String> terms = new ArrayList<>();
        if (unread || readOnly) terms.add(equalTo("message:IsRead", readOnly ? "true" : "false"));
        if (withAtts) terms.add(equalTo("item:HasAttachments", "true"));
        if (since != null) terms.add(compare("IsGreaterThanOrEqualTo", "item:DateTimeReceived", utc(since)));
        if (before != null) terms.add(compare("IsLessThan", "item:DateTimeReceived", utc(before)));
        List<String> subj = new ArrayList<>();
        for (String v : parts(subject))
            subj.add("<t:Contains ContainmentMode=\"Substring\" ContainmentComparison=\"IgnoreCase\"><t:FieldURI FieldURI=\"item:Subject\"/><t:Constant Value=\"" + x(v) + "\"/></t:Contains>");
        if (subj.size() == 1) terms.add(subj.get(0)); else if (subj.size() > 1) terms.add("<t:Or>" + String.join("", subj) + "</t:Or>");
        List<String> inBody = new ArrayList<>();
        for (String v : parts(bodyContains))
            inBody.add("<t:Contains ContainmentMode=\"Substring\" ContainmentComparison=\"IgnoreCase\"><t:FieldURI FieldURI=\"item:Body\"/><t:Constant Value=\"" + x(v) + "\"/></t:Contains>");
        if (inBody.size() == 1) terms.add(inBody.get(0)); else if (inBody.size() > 1) terms.add("<t:Or>" + String.join("", inBody) + "</t:Or>");
        String restriction = terms.isEmpty() ? "" : "<m:Restriction>" + (terms.size() == 1 ? terms.get(0) : "<t:And>" + String.join("", terms) + "</t:And>") + "</m:Restriction>";
        List<String> senders = parts(from);
        // the sender is matched here, as EWS cannot search the From address as text; pages are then larger
        int page = senders.isEmpty() ? Math.min(limit, 250) : 250;
        String parent = folderElement(ctx, s, folder, false);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int offset = 0; out.size() < limit; ) {
            String body = "<m:FindItem Traversal=\"Shallow\"><m:ItemShape><t:BaseShape>IdOnly</t:BaseShape><t:AdditionalProperties>"
                    + "<t:FieldURI FieldURI=\"item:Subject\"/><t:FieldURI FieldURI=\"item:DateTimeReceived\"/><t:FieldURI FieldURI=\"message:From\"/>"
                    + "<t:FieldURI FieldURI=\"message:IsRead\"/><t:FieldURI FieldURI=\"item:HasAttachments\"/></t:AdditionalProperties></m:ItemShape>"
                    + "<m:IndexedPageItemView MaxEntriesReturned=\"" + page + "\" Offset=\"" + offset + "\" BasePoint=\"Beginning\"/>" + restriction
                    + "<m:SortOrder><t:FieldOrder Order=\"" + (oldestFirst ? "Ascending" : "Descending") + "\"><t:FieldURI FieldURI=\"item:DateTimeReceived\"/></t:FieldOrder></m:SortOrder>"
                    + "<m:ParentFolderIds>" + parent + "</m:ParentFolderIds></m:FindItem>";
            Element msg = call(ctx, s, "FindItem", body);
            Element root = child(msg, "RootFolder");
            if (root == null) break;
            List<Element> items = children(child(root, "Items"));
            for (Element it : items) {
                Map<String, Object> e = new LinkedHashMap<>();
                String sender = address(child(it, "From"));
                if (!senders.isEmpty() && senders.stream().noneMatch(v -> sender.toLowerCase(Locale.ROOT).contains(v.toLowerCase(Locale.ROOT)))) continue;
                e.put("id", attr(child(it, "ItemId"), "Id"));
                e.put("from", sender);
                e.put("subject", text(it, "Subject"));
                e.put("received", time(text(it, "DateTimeReceived")));
                e.put("unread", !"true".equals(text(it, "IsRead")));
                e.put("has_attachments", "true".equals(text(it, "HasAttachments")));
                out.add(e);
                if (out.size() >= limit) break;
            }
            offset += items.size();
            if (items.isEmpty() || "true".equals(root.getAttribute("IncludesLastItemInRange"))) break;
        }
        return out;
    }

    @Action(id = "get", name = "Read email", category = "Mailbox", idempotent = true, capabilities = "network",
            description = "Read one email: sender, recipients, subject, text, HTML and attachment names.",
            panel = "id, mark_read | session")
    @Output(name = "email", type = OoaType.DICTIONARY, description = "id, from, to, cc, subject, received, text, html, attachments, reply_to, sent, message_id, importance, flagged, size, unread, conversation_id. Also, the same on every mail service: id, from, to, cc, subject, received, text, html, attachments (names), has_attachments, unread, message_id.")
    public Map<String, Object> get(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, description = D_ID) String id,
            @Input(name = "mark_read", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Mark the email as read.") boolean markRead,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"exchange\"", description = D_SESSION) String session) {
        ExchangeSession s = es(ctx, session);
        Element it = item(ctx, s, id, "<t:BaseShape>Default</t:BaseShape><t:BodyType>Best</t:BodyType><t:AdditionalProperties>"
                + "<t:FieldURI FieldURI=\"item:Body\"/><t:FieldURI FieldURI=\"item:TextBody\"/><t:FieldURI FieldURI=\"item:Attachments\"/>"
                + "<t:FieldURI FieldURI=\"message:CcRecipients\"/><t:FieldURI FieldURI=\"message:ReplyTo\"/><t:FieldURI FieldURI=\"item:DateTimeSent\"/>"
                + "<t:FieldURI FieldURI=\"message:InternetMessageId\"/><t:FieldURI FieldURI=\"item:Importance\"/><t:FieldURI FieldURI=\"item:Size\"/>"
                + "<t:FieldURI FieldURI=\"item:Flag\"/><t:FieldURI FieldURI=\"item:ConversationId\"/></t:AdditionalProperties>");
        Element body = child(it, "Body");
        boolean html = body != null && "HTML".equalsIgnoreCase(body.getAttribute("BodyType"));
        String textBody = text(it, "TextBody");
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", attr(child(it, "ItemId"), "Id"));
        e.put("from", address(child(it, "From")));
        e.put("to", addresses(child(it, "ToRecipients")));
        e.put("cc", addresses(child(it, "CcRecipients")));
        e.put("subject", text(it, "Subject"));
        e.put("received", time(text(it, "DateTimeReceived")));
        e.put("text", !textBody.isEmpty() || html ? textBody : body == null ? "" : body.getTextContent());
        e.put("html", html ? body.getTextContent() : "");
        List<Object> names = new ArrayList<>();
        for (Element a : children(child(it, "Attachments"))) names.add(text(a, "Name"));
        e.put("attachments", names);
        e.put("reply_to", addresses(child(it, "ReplyTo")));
        e.put("sent", time(text(it, "DateTimeSent")));
        e.put("message_id", text(it, "InternetMessageId"));
        e.put("importance", text(it, "Importance").toLowerCase(Locale.ROOT).isEmpty() ? "normal" : text(it, "Importance").toLowerCase(Locale.ROOT));
        Element flag = child(it, "Flag");
        e.put("flagged", flag != null && "Flagged".equals(text(flag, "FlagStatus")));
        String size = text(it, "Size");
        e.put("size", new BigDecimal(size.matches("\\d+") ? size : "0"));
        boolean wasUnread = "false".equals(text(it, "IsRead"));
        e.put("unread", wasUnread && !markRead);
        e.put("conversation_id", attr(child(it, "ConversationId"), "Id"));
        if (markRead && wasUnread) setFields(ctx, s, id, isRead(true));
        return Map.of("email", MailRecord.withCommonKeys(e));
    }

    @Action(id = "save_attachments", name = "Save attachments", category = "Mailbox", capabilities = {"network", "filesystem"},
            description = "Save an email's file attachments to a folder, optionally only names matching a pattern such as *.pdf.",
            panel = "id, target_folder, overwrite | name_pattern, include_inline, session")
    @Output(name = "files", type = OoaType.LIST, description = "Saved file paths.")
    public Map<String, Object> saveAttachments(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, description = D_ID) String id,
            @Input(name = "target_folder", type = OoaType.FILE, description = "Folder to save into.") String target,
            @Input(name = "name_pattern", type = OoaType.STRING, required = false, defaultValue = "\"*\"", description = "Only names matching this pattern, e.g. *.pdf.") String pattern,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Replace files of the same name instead of adding a number such as (2).") boolean overwrite,
            @Input(name = "include_inline", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Also save images embedded in the body, such as signature logos.") boolean inline,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"exchange\"", description = D_SESSION) String session) {
        ExchangeSession s = es(ctx, session);
        Element it = item(ctx, s, id, "<t:BaseShape>IdOnly</t:BaseShape><t:AdditionalProperties><t:FieldURI FieldURI=\"item:Attachments\"/></t:AdditionalProperties>");
        PathMatcher pm = FileSystems.getDefault().getPathMatcher("glob:" + (pattern == null || pattern.isBlank() ? "*" : pattern).toLowerCase(Locale.ROOT));
        Path dir = ctx.resolvePath(target);
        List<Object> saved = new ArrayList<>();
        try {
            Files.createDirectories(dir);
            for (Element a : children(child(it, "Attachments"))) {
                if (!"FileAttachment".equals(a.getLocalName())) continue;   // an attached email or link is not a file
                if (!inline && "true".equals(text(a, "IsInline"))) continue;
                String name = HttpJson.safeName(text(a, "Name"));
                if (!pm.matches(Path.of(name.toLowerCase(Locale.ROOT)))) continue;
                Element msg = call(ctx, s, "GetAttachment", "<m:GetAttachment><m:AttachmentIds><t:AttachmentId Id=\"" + x(attr(child(a, "AttachmentId"), "Id"))
                        + "\"/></m:AttachmentIds></m:GetAttachment>");
                Element file = first(child(msg, "Attachments"));
                String content = file == null ? "" : text(file, "Content");
                Path out = dir.resolve(name);
                if (!overwrite) for (int i = 2; Files.exists(out); i++) out = dir.resolve(name.replaceFirst("(\\.[^.]+)?$", " (" + i + ")$1"));
                Files.write(out, Base64.getMimeDecoder().decode(content));
                saved.add(out.toString());
            }
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), "Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it.", true); }
        return Map.of("files", saved);
    }

    @Action(id = "get_folders", name = "Get folders", category = "Mailbox", idempotent = true, capabilities = "network",
            description = "List all mail folders of the mailbox as paths such as Inbox/Invoices.",
            panel = "session")
    @Output(name = "folders", type = OoaType.LIST, description = "Folder paths.")
    public Map<String, Object> getFolders(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"exchange\"", description = D_SESSION) String session) {
        ExchangeSession s = es(ctx, session);
        Element msg = call(ctx, s, "FindFolder", "<m:FindFolder Traversal=\"Deep\"><m:FolderShape><t:BaseShape>IdOnly</t:BaseShape><t:AdditionalProperties>"
                + "<t:FieldURI FieldURI=\"folder:DisplayName\"/><t:FieldURI FieldURI=\"folder:ParentFolderId\"/></t:AdditionalProperties></m:FolderShape>"
                + "<m:ParentFolderIds>" + s.distinguished("msgfolderroot") + "</m:ParentFolderIds></m:FindFolder>");
        Element root = child(msg, "RootFolder");
        Map<String, String[]> byId = new LinkedHashMap<>();   // id -> name, parent id
        for (Element f : children(root == null ? null : child(root, "Folders")))
            byId.put(attr(child(f, "FolderId"), "Id"), new String[]{text(f, "DisplayName"), attr(child(f, "ParentFolderId"), "Id")});
        List<Object> out = new ArrayList<>();
        for (String fid : byId.keySet()) {
            Deque<String> path = new ArrayDeque<>();
            for (String at = fid; at != null && byId.containsKey(at) && path.size() < 64; at = byId.get(at)[1]) path.addFirst(byId.get(at)[0]);
            out.add(String.join("/", path));
        }
        return Map.of("folders", out);
    }

    // ------------------------------------------------------------------ send, reply, forward

    @Action(id = "send", name = "Send email", category = "Send", timeoutSeconds = 120, capabilities = "network",
            description = "Send an email (plain text or HTML) with optional CC, BCC and attachments from the Exchange mailbox, keeping a copy in Sent Items.",
            panel = "to, cc, bcc, subject, attachments, body, html | from, reply_to, importance, read_receipt, save_to_sent_items, session")
    public Map<String, Object> send(ActionContext ctx,
            @Input(name = "to", description = "Recipient address(es); separate several with ; or give a list.") Object to,
            @Input(name = "subject", type = OoaType.STRING, description = "Subject.") String subject,
            @Input(name = "body", type = OoaType.STRING, required = false, defaultValue = "\"\"", description = "Message body.") String body,
            @Input(name = "html", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Body is HTML.") boolean html,
            @Input(name = "cc", required = false, description = "CC address(es).") Object cc,
            @Input(name = "bcc", required = false, description = "BCC address(es).") Object bcc,
            @Input(name = "attachments", type = OoaType.LIST, required = false, description = "File paths to attach.") List<Object> attachments,
            @Input(name = "from", type = OoaType.STRING, required = false, example = "invoices@acme.com",
                    description = "Send as this address, e.g. a shared mailbox; the user needs Send As or Send on Behalf rights. The session's mailbox when empty.") String sendAs,
            @Input(name = "reply_to", required = false, description = "Address(es) that replies go to, when not the sender.") Object replyTo,
            @Input(name = "importance", type = OoaType.STRING, required = false, defaultValue = "\"normal\"", enumValues = {"low", "normal", "high"}, description = "Importance shown to the recipients.") String importance,
            @Input(name = "read_receipt", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Ask for a receipt when the email is read.") boolean receipt,
            @Input(name = "save_to_sent_items", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Keep a copy in Sent Items.") boolean saveSent,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"exchange\"", description = D_SESSION) String session) {
        ExchangeSession s = es(ctx, session);
        List<String> recipients = HttpJson.addressList(to);
        if (recipients.isEmpty()) throw new ActionException(P + ".BAD_ADDRESS", "Give at least one recipient in to", "Fill in to.");
        StringBuilder m = new StringBuilder("<t:Message><t:Subject>").append(x(subject == null ? "" : subject)).append("</t:Subject>")
                .append("<t:Body BodyType=\"").append(html ? "HTML" : "Text").append("\">").append(x(body == null ? "" : body)).append("</t:Body>");
        String files = attachmentsXml(ctx, attachments);
        m.append(files);
        String imp = importance == null || importance.isBlank() ? "normal" : importance.strip().toLowerCase(Locale.ROOT);
        if (!imp.equals("normal")) m.append("<t:Importance>").append(imp.equals("high") ? "High" : "Low").append("</t:Importance>");
        m.append(mailboxes("ToRecipients", recipients)).append(mailboxes("CcRecipients", HttpJson.addressList(cc))).append(mailboxes("BccRecipients", HttpJson.addressList(bcc)));
        if (receipt) m.append("<t:IsReadReceiptRequested>true</t:IsReadReceiptRequested>");
        String fromAddress = sendAs != null && !sendAs.isBlank() ? sendAs.strip() : s.otherMailbox() && !s.impersonate ? s.mailbox : null;
        if (fromAddress != null) m.append("<t:From><t:Mailbox><t:EmailAddress>").append(x(fromAddress)).append("</t:EmailAddress></t:Mailbox></t:From>");
        m.append(mailboxes("ReplyTo", HttpJson.addressList(replyTo))).append("</t:Message>");
        String sentFolder = "<m:SavedItemFolderId>" + s.distinguished("sentitems") + "</m:SavedItemFolderId>";
        if (files.isEmpty()) {
            call(ctx, s, "CreateItem", "<m:CreateItem MessageDisposition=\"" + (saveSent ? "SendAndSaveCopy\">" + sentFolder : "SendOnly\">")
                    + "<m:Items>" + m + "</m:Items></m:CreateItem>");
        } else {
            // with attachments: saved as a draft first, then sent, which every Exchange version accepts
            Element made = call(ctx, s, "CreateItem", "<m:CreateItem MessageDisposition=\"SaveOnly\"><m:SavedItemFolderId>" + s.distinguished("drafts")
                    + "</m:SavedItemFolderId><m:Items>" + m + "</m:Items></m:CreateItem>");
            Element draftId = child(first(child(made, "Items")), "ItemId");
            call(ctx, s, "SendItem", "<m:SendItem SaveItemToFolder=\"" + saveSent + "\"><m:ItemIds>" + itemId(draftId) + "</m:ItemIds>"
                    + (saveSent ? sentFolder : "") + "</m:SendItem>");
        }
        return Map.of();
    }

    @Action(id = "reply", name = "Reply to email", category = "Send", timeoutSeconds = 120, capabilities = "network",
            description = "Reply to the sender of an email, keeping it in the same conversation with the original quoted below.",
            panel = "id, body, html, attachments | cc, bcc, session")
    public Map<String, Object> reply(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, description = D_ID) String id,
            @Input(name = "body", type = OoaType.STRING, required = false, defaultValue = "\"\"", description = "Reply text, shown above the original.") String body,
            @Input(name = "html", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Body is HTML.") boolean html,
            @Input(name = "cc", required = false, description = "Extra CC address(es).") Object cc,
            @Input(name = "bcc", required = false, description = "BCC address(es).") Object bcc,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"exchange\"", description = D_SESSION) String session,
            @Input(name = "attachments", type = OoaType.LIST, required = false, description = D_RESPONSE_FILES) List<Object> attachments) {
        respond(ctx, es(ctx, session), "ReplyToItem", id, null, cc, bcc, body, html, attachments, false);
        return Map.of();
    }

    /** Earlier signature, kept for callers written before attachments. */
    public Map<String, Object> reply(ActionContext ctx, String id, String body, boolean html, Object cc, Object bcc, String session) {
        return reply(ctx, id, body, html, cc, bcc, session, null);
    }

    @Action(id = "reply_all", name = "Reply to all", category = "Send", timeoutSeconds = 120, capabilities = "network",
            description = "Reply to the sender and all other recipients of an email, keeping it in the same conversation with the original quoted below.",
            panel = "id, body, html, attachments | cc, bcc, exclude_sender, session")
    public Map<String, Object> replyAll(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, description = D_ID) String id,
            @Input(name = "body", type = OoaType.STRING, required = false, defaultValue = "\"\"", description = "Reply text, shown above the original.") String body,
            @Input(name = "html", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Body is HTML.") boolean html,
            @Input(name = "cc", required = false, description = "Extra CC address(es).") Object cc,
            @Input(name = "bcc", required = false, description = "BCC address(es).") Object bcc,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"exchange\"", description = D_SESSION) String session,
            @Input(name = "attachments", type = OoaType.LIST, required = false, description = D_RESPONSE_FILES) List<Object> attachments,
            @Input(name = "exclude_sender", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    description = "Leave the original sender out, replying only to the other recipients (e.g. to tell the team, not the customer).") boolean excludeSender) {
        respond(ctx, es(ctx, session), "ReplyAllToItem", id, null, cc, bcc, body, html, attachments, excludeSender);
        return Map.of();
    }

    /** Earlier signature, kept for callers written before attachments and exclude_sender. */
    public Map<String, Object> replyAll(ActionContext ctx, String id, String body, boolean html, Object cc, Object bcc, String session) {
        return replyAll(ctx, id, body, html, cc, bcc, session, null, false);
    }

    @Action(id = "forward", name = "Forward email", category = "Send", timeoutSeconds = 120, capabilities = "network",
            description = "Forward an email, with its attachments, and a note shown above the original.",
            panel = "id, to, cc, bcc, note, html, attachments | as_attachment, session")
    public Map<String, Object> forward(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, description = D_ID) String id,
            @Input(name = "to", description = "Recipient address(es).") Object to,
            @Input(name = "note", type = OoaType.STRING, required = false, defaultValue = "\"\"", description = "Text shown above the forwarded email.") String note,
            @Input(name = "html", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "The note is HTML.") boolean html,
            @Input(name = "cc", required = false, description = "CC address(es).") Object cc,
            @Input(name = "bcc", required = false, description = "BCC address(es).") Object bcc,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"exchange\"", description = D_SESSION) String session,
            @Input(name = "attachments", type = OoaType.LIST, required = false, description = D_RESPONSE_FILES) List<Object> attachments,
            @Input(name = "as_attachment", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    description = "Attach the original email as an .eml file instead of quoting it below the note.") boolean asAttachment) {
        List<String> recipients = HttpJson.addressList(to);
        if (recipients.isEmpty()) throw new ActionException(P + ".BAD_ADDRESS", "Give at least one recipient in to", "Fill in to.");
        ExchangeSession s = es(ctx, session);
        if (asAttachment) forwardAsAttachment(ctx, s, id, recipients, cc, bcc, note, html, attachments);
        else respond(ctx, s, "ForwardItem", id, recipients, cc, bcc, note, html, attachments, false);
        return Map.of();
    }

    /** Earlier signature, kept for callers written before attachments and as_attachment. */
    public Map<String, Object> forward(ActionContext ctx, String id, Object to, String note, boolean html, Object cc, Object bcc, String session) {
        return forward(ctx, id, to, note, html, cc, bcc, session, null, false);
    }

    /** A new email with the original attached as an .eml file (its full MIME content), sent through a draft. */
    private static void forwardAsAttachment(ActionContext ctx, ExchangeSession s, String id, List<String> to, Object cc, Object bcc, String note, boolean html,
                                            List<Object> attachments) {
        Element original = item(ctx, s, id, "<t:BaseShape>IdOnly</t:BaseShape><t:IncludeMimeContent>true</t:IncludeMimeContent>"
                + "<t:AdditionalProperties><t:FieldURI FieldURI=\"item:Subject\"/></t:AdditionalProperties>");
        String subject = text(original, "Subject"), mime = text(original, "MimeContent");
        if (mime.isEmpty()) throw new ActionException(P + ".BAD_RESPONSE", "Exchange did not give the email's content", "Forward it without as_attachment.");
        String extra = attachmentsXml(ctx, attachments);
        String files = "<t:FileAttachment><t:Name>" + x(emlName(subject)) + "</t:Name><t:Content>" + x(mime) + "</t:Content></t:FileAttachment>"
                + (extra.isEmpty() ? "" : extra.substring("<t:Attachments>".length(), extra.length() - "</t:Attachments>".length()));
        String m = "<t:Message><t:Subject>" + x("FW: " + subject) + "</t:Subject><t:Body BodyType=\"" + (html ? "HTML" : "Text") + "\">" + x(note == null ? "" : note) + "</t:Body>"
                + "<t:Attachments>" + files + "</t:Attachments>"
                + mailboxes("ToRecipients", to) + mailboxes("CcRecipients", HttpJson.addressList(cc)) + mailboxes("BccRecipients", HttpJson.addressList(bcc)) + "</t:Message>";
        Element made = call(ctx, s, "CreateItem", "<m:CreateItem MessageDisposition=\"SaveOnly\"><m:SavedItemFolderId>" + s.distinguished("drafts")
                + "</m:SavedItemFolderId><m:Items>" + m + "</m:Items></m:CreateItem>");
        sendDraft(ctx, s, child(first(child(made, "Items")), "ItemId"));
    }

    /** A file name for an email: its subject without the characters Windows does not allow, ending in .eml. */
    static String emlName(String subject) {
        String n = (subject == null || subject.isBlank() ? "email" : subject.strip()).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        return (n.length() > 120 ? n.substring(0, 120) : n) + ".eml";
    }

    /**
     * Reply, reply all or forward: a response object that points at the original (with its change key, which EWS asks
     * for). With attachments, or without the original sender, it is saved as a draft first: Exchange takes neither on
     * the response itself, so the draft gets its files and recipients and is then sent (A6, command review batch 7).
     */
    private static void respond(ActionContext ctx, ExchangeSession s, String kind, String id, List<String> to, Object cc, Object bcc, String text, boolean html,
                                List<Object> attachments, boolean excludeSender) {
        String fileXml = attachmentsXml(ctx, attachments);   // read first, so a missing file leaves no draft behind
        Element original = item(ctx, s, id, excludeSender
                ? "<t:BaseShape>IdOnly</t:BaseShape><t:AdditionalProperties><t:FieldURI FieldURI=\"message:From\"/></t:AdditionalProperties>"
                : "<t:BaseShape>IdOnly</t:BaseShape>");
        String response = "<t:" + kind + ">"
                + (to == null ? "" : mailboxes("ToRecipients", to)) + mailboxes("CcRecipients", HttpJson.addressList(cc)) + mailboxes("BccRecipients", HttpJson.addressList(bcc))
                + itemId(child(original, "ItemId")).replace("<t:ItemId", "<t:ReferenceItemId")
                + "<t:NewBodyContent BodyType=\"" + (html ? "HTML" : "Text") + "\">" + x(text == null ? "" : text) + "</t:NewBodyContent>"
                + "</t:" + kind + ">";
        if (fileXml.isEmpty() && !excludeSender) {
            call(ctx, s, "CreateItem", "<m:CreateItem MessageDisposition=\"SendAndSaveCopy\"><m:SavedItemFolderId>" + s.distinguished("sentitems")
                    + "</m:SavedItemFolderId><m:Items>" + response + "</m:Items></m:CreateItem>");
            return;
        }
        Element made = call(ctx, s, "CreateItem", "<m:CreateItem MessageDisposition=\"SaveOnly\"><m:SavedItemFolderId>" + s.distinguished("drafts")
                + "</m:SavedItemFolderId><m:Items>" + response + "</m:Items></m:CreateItem>");
        Element draft = child(first(child(made, "Items")), "ItemId");
        if (excludeSender) draft = withoutSender(ctx, s, draft, address(child(original, "From")));
        if (!fileXml.isEmpty()) {
            String inner = fileXml.substring("<t:Attachments>".length(), fileXml.length() - "</t:Attachments>".length());
            Element added = call(ctx, s, "CreateAttachment", "<m:CreateAttachment><m:ParentItemId Id=\"" + x(attr(draft, "Id")) + "\" ChangeKey=\""
                    + x(attr(draft, "ChangeKey")) + "\"/><m:Attachments>" + inner + "</m:Attachments></m:CreateAttachment>");
            String ck = attr(child(first(child(added, "Attachments")), "AttachmentId"), "RootItemChangeKey");
            if (!ck.isEmpty()) draft.setAttribute("ChangeKey", ck);
        }
        sendDraft(ctx, s, draft);
    }

    /** Takes the original sender off a reply-all draft's To and CC; returns the draft's id with its new change key. */
    private static Element withoutSender(ActionContext ctx, ExchangeSession s, Element draft, String sender) {
        Element d = item(ctx, s, attr(draft, "Id"), "<t:BaseShape>IdOnly</t:BaseShape><t:AdditionalProperties><t:FieldURI FieldURI=\"message:ToRecipients\"/>"
                + "<t:FieldURI FieldURI=\"message:CcRecipients\"/></t:AdditionalProperties>");
        List<String> keepTo = new ArrayList<>(), keepCc = new ArrayList<>();
        for (Element m : children(child(d, "ToRecipients"))) { String a = text(m, "EmailAddress"); if (!a.isEmpty() && !a.equalsIgnoreCase(sender)) keepTo.add(a); }
        for (Element m : children(child(d, "CcRecipients"))) { String a = text(m, "EmailAddress"); if (!a.isEmpty() && !a.equalsIgnoreCase(sender)) keepCc.add(a); }
        if (keepTo.isEmpty() && keepCc.isEmpty()) {
            call(ctx, s, "DeleteItem", "<m:DeleteItem DeleteType=\"HardDelete\"><m:ItemIds>" + itemId(draft) + "</m:ItemIds></m:DeleteItem>");
            throw new ActionException(P + ".BAD_ADDRESS", "Without the sender there is nobody left to reply to", "Use Reply to email, or give cc.");
        }
        String updates = (keepTo.isEmpty() ? "<t:DeleteItemField><t:FieldURI FieldURI=\"message:ToRecipients\"/></t:DeleteItemField>"
                : "<t:SetItemField><t:FieldURI FieldURI=\"message:ToRecipients\"/><t:Message>" + mailboxes("ToRecipients", keepTo) + "</t:Message></t:SetItemField>")
                + (keepCc.isEmpty() ? "<t:DeleteItemField><t:FieldURI FieldURI=\"message:CcRecipients\"/></t:DeleteItemField>"
                : "<t:SetItemField><t:FieldURI FieldURI=\"message:CcRecipients\"/><t:Message>" + mailboxes("CcRecipients", keepCc) + "</t:Message></t:SetItemField>");
        Element msg = call(ctx, s, "UpdateItem", "<m:UpdateItem MessageDisposition=\"SaveOnly\" ConflictResolution=\"AlwaysOverwrite\"><m:ItemChanges><t:ItemChange>"
                + itemId(draft) + "<t:Updates>" + updates + "</t:Updates></t:ItemChange></m:ItemChanges></m:UpdateItem>");
        Element updated = child(first(child(msg, "Items")), "ItemId");
        return updated == null ? draft : updated;
    }

    /** Sends a saved draft and keeps it in Sent Items. */
    private static void sendDraft(ActionContext ctx, ExchangeSession s, Element draftId) {
        call(ctx, s, "SendItem", "<m:SendItem SaveItemToFolder=\"true\"><m:ItemIds>" + itemId(draftId) + "</m:ItemIds><m:SavedItemFolderId>"
                + s.distinguished("sentitems") + "</m:SavedItemFolderId></m:SendItem>");
    }

    @Action(id = "save_email_as_eml", name = "Save email as .eml", category = "Mailbox", capabilities = {"network", "filesystem"},
            description = "Save a whole email (text, HTML, attachments and headers) as an .eml file that any mail program opens.",
            panel = "id, local_path | overwrite, session")
    @Output(name = "path", type = OoaType.FILE, description = "The saved file.")
    public Map<String, Object> saveEmailAsEml(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, description = D_ID) String id,
            @Input(name = "local_path", type = OoaType.FILE, description = "The .eml file to write, or a folder: the subject then names the file.") String localPath,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace a file that is already there; when false it stops the step.") boolean overwrite,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"exchange\"", description = D_SESSION) String session) {
        ExchangeSession s = es(ctx, session);
        Element it = item(ctx, s, id, "<t:BaseShape>IdOnly</t:BaseShape><t:IncludeMimeContent>true</t:IncludeMimeContent>"
                + "<t:AdditionalProperties><t:FieldURI FieldURI=\"item:Subject\"/></t:AdditionalProperties>");
        String mime = text(it, "MimeContent");
        if (mime.isEmpty()) throw new ActionException(P + ".BAD_RESPONSE", "Exchange did not give the email's content", "The service answered in an unexpected way; try again, and if it keeps happening check the service's status page.");
        Path p = ctx.resolvePath(localPath);
        if (Files.isDirectory(p)) p = p.resolve(emlName(text(it, "Subject")));
        if (!overwrite && Files.exists(p)) throw new ActionException("FILE.EXISTS", "File already exists: " + p, "Set overwrite to true or choose another name.");
        try {
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            Files.write(p, Base64.getMimeDecoder().decode(mime));
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), "Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it.", true); }
        return Map.of("path", p.toString());
    }

    // ------------------------------------------------------------------ move, mark, delete

    @Action(id = "move", name = "Move email", category = "Mailbox", capabilities = "network",
            description = "Move an email to another folder, e.g. Inbox/Processed; returns its new id, as Exchange gives a moved email a new one.",
            panel = "id, to_folder | create_folder, session")
    @Output(name = "new_id", type = OoaType.STRING, description = "The email's id in the destination folder.")
    public Map<String, Object> move(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, description = D_ID) String id,
            @Input(name = "to_folder", type = OoaType.STRING, description = "Destination: a well-known name such as archive, a path such as Inbox/Processed, or a folder id.") String to,
            @Input(name = "create_folder", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Create the destination folder (and the folders on its path) when it does not exist.") boolean create,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"exchange\"", description = D_SESSION) String session) {
        ExchangeSession s = es(ctx, session);
        return Out.of("new_id", moveTo(ctx, s, id, folderElement(ctx, s, to, create)));
    }

    private static String moveTo(ActionContext ctx, ExchangeSession s, String id, String folder) {
        Element msg = call(ctx, s, "MoveItem", "<m:MoveItem><m:ToFolderId>" + folder + "</m:ToFolderId><m:ItemIds><t:ItemId Id=\"" + x(need(id)) + "\"/></m:ItemIds>"
                + "<m:ReturnNewItemIds>true</m:ReturnNewItemIds></m:MoveItem>");
        Element moved = first(child(msg, "Items"));
        String newId = moved == null ? "" : attr(child(moved, "ItemId"), "Id");
        return newId.isEmpty() ? null : newId;
    }

    @Action(id = "mark", name = "Mark read or unread", category = "Mailbox", capabilities = "network",
            description = "Mark an email as read or unread, and optionally set or clear its follow-up flag.",
            panel = "id, read | flagged, session")
    public Map<String, Object> mark(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, description = D_ID) String id,
            @Input(name = "read", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "True = read, false = unread.") boolean read,
            @Input(name = "flagged", type = OoaType.BOOLEAN, required = false, description = "Set (true) or clear (false) the follow-up flag; unchanged when empty.") Boolean flagged,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"exchange\"", description = D_SESSION) String session) {
        String updates = isRead(read);
        if (flagged != null) updates += "<t:SetItemField><t:FieldURI FieldURI=\"item:Flag\"/><t:Message><t:Flag><t:FlagStatus>" + (flagged ? "Flagged" : "NotFlagged")
                + "</t:FlagStatus></t:Flag></t:Message></t:SetItemField>";
        setFields(ctx, es(ctx, session), id, updates);
        return Map.of();
    }

    @Action(id = "delete", name = "Delete email", category = "Mailbox", capabilities = "network",
            description = "Delete an email: moved to Deleted Items, or with permanent removed from the folders (Exchange still keeps it in Recoverable Items for its retention time).",
            panel = "id | permanent, session")
    public Map<String, Object> delete(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, description = D_ID) String id,
            @Input(name = "permanent", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    description = "Remove the email instead of moving it to Deleted Items; only an administrator can bring it back.") boolean permanent,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"exchange\"", description = D_SESSION) String session) {
        call(ctx, es(ctx, session), "DeleteItem", "<m:DeleteItem DeleteType=\"" + (permanent ? "SoftDelete" : "MoveToDeletedItems") + "\"><m:ItemIds><t:ItemId Id=\""
                + x(need(id)) + "\"/></m:ItemIds></m:DeleteItem>");
        return Map.of();
    }

    // ------------------------------------------------------------------ helpers: items, folders, XML

    private static String need(String id) {
        if (id == null || id.isBlank()) throw new ActionException(P + ".BAD_INPUT", "Give the email id", "Use an id from Find emails.");
        return id.strip();
    }

    /** One item by id with the given ItemShape content. */
    private static Element item(ActionContext ctx, ExchangeSession s, String id, String shape) {
        Element msg = call(ctx, s, "GetItem", "<m:GetItem><m:ItemShape>" + shape + "</m:ItemShape><m:ItemIds><t:ItemId Id=\"" + x(need(id)) + "\"/></m:ItemIds></m:GetItem>");
        Element it = first(child(msg, "Items"));
        if (it == null) throw new ActionException(P + ".NOT_FOUND", "No email with id " + id, "The email may have been moved (it then has a new id) or deleted.");
        return it;
    }

    private static String isRead(boolean read) {
        return "<t:SetItemField><t:FieldURI FieldURI=\"message:IsRead\"/><t:Message><t:IsRead>" + read + "</t:IsRead></t:Message></t:SetItemField>";
    }

    private static void setFields(ActionContext ctx, ExchangeSession s, String id, String updates) {
        call(ctx, s, "UpdateItem", "<m:UpdateItem MessageDisposition=\"SaveOnly\" ConflictResolution=\"AutoResolve\"><m:ItemChanges><t:ItemChange><t:ItemId Id=\""
                + x(need(id)) + "\"/><t:Updates>" + updates + "</t:Updates></t:ItemChange></m:ItemChanges></m:UpdateItem>");
    }

    private static String itemId(Element id) {
        if (id == null) throw new ActionException(P + ".BAD_RESPONSE", "Exchange did not return the item's id", "The service answered in an unexpected way; try again, and if it keeps happening check the service's status page.");
        String ck = id.getAttribute("ChangeKey");
        return "<t:ItemId Id=\"" + x(id.getAttribute("Id")) + "\"" + (ck.isEmpty() ? "" : " ChangeKey=\"" + x(ck) + "\"") + "/>";
    }

    /** The FolderId (or DistinguishedFolderId) element for a well-known name, a path from the top of the mailbox, or a folder id. */
    static String folderElement(ActionContext ctx, ExchangeSession s, String folder, boolean create) {
        String f = folder == null || folder.isBlank() ? "inbox" : folder.strip();
        String known = WELL_KNOWN.get(f.toLowerCase(Locale.ROOT));
        if (known != null) return s.distinguished(known);
        // EWS ids are long base64 text; a folder name has spaces, or is short
        if (f.length() > 60 && f.matches("[A-Za-z0-9+/=_-]+")) return "<t:FolderId Id=\"" + x(f) + "\"/>";
        String cached = s.folderIds.get(f.toLowerCase(Locale.ROOT));
        if (cached != null) return "<t:FolderId Id=\"" + x(cached) + "\"/>";
        String[] segments = f.replace('\\', '/').split("/");
        String parent;
        int start = 0;
        String first = WELL_KNOWN.get(segments[0].strip().toLowerCase(Locale.ROOT));
        if (first != null) { parent = s.distinguished(first); start = 1; } else parent = s.distinguished("msgfolderroot");
        String id = null;
        for (int i = start; i < segments.length; i++) {
            String name = segments[i].strip();
            if (name.isEmpty()) continue;
            id = childFolder(ctx, s, parent, name);
            if (id == null) {
                if (!create) throw new ActionException(P + ".NO_FOLDER", "Mail folder '" + f + "' not found (no '" + name + "')",
                        "Use Get folders to see the exact names, or set create_folder to true.");
                Element msg = call(ctx, s, "CreateFolder", "<m:CreateFolder><m:ParentFolderId>" + parent + "</m:ParentFolderId><m:Folders><t:Folder>"
                        + "<t:FolderClass>IPF.Note</t:FolderClass><t:DisplayName>" + x(name) + "</t:DisplayName></t:Folder></m:Folders></m:CreateFolder>");
                id = attr(child(first(child(msg, "Folders")), "FolderId"), "Id");
            }
            parent = "<t:FolderId Id=\"" + x(id) + "\"/>";
        }
        if (id == null) return parent;
        s.folderIds.put(f.toLowerCase(Locale.ROOT), id);
        return parent;
    }

    /** The id of the folder directly under parent with this name (case ignored), or null. */
    private static String childFolder(ActionContext ctx, ExchangeSession s, String parent, String name) {
        Element msg = call(ctx, s, "FindFolder", "<m:FindFolder Traversal=\"Shallow\"><m:FolderShape><t:BaseShape>IdOnly</t:BaseShape><t:AdditionalProperties>"
                + "<t:FieldURI FieldURI=\"folder:DisplayName\"/></t:AdditionalProperties></m:FolderShape>"
                + "<m:IndexedPageFolderView MaxEntriesReturned=\"1000\" Offset=\"0\" BasePoint=\"Beginning\"/>"
                + "<m:ParentFolderIds>" + parent + "</m:ParentFolderIds></m:FindFolder>");
        Element root = child(msg, "RootFolder");
        for (Element f : children(root == null ? null : child(root, "Folders")))
            if (text(f, "DisplayName").equalsIgnoreCase(name)) return attr(child(f, "FolderId"), "Id");
        return null;
    }

    private static String attachmentsXml(ActionContext ctx, List<Object> attachments) {
        if (attachments == null || attachments.isEmpty()) return "";
        StringBuilder b = new StringBuilder("<t:Attachments>");
        for (Object a : attachments) {
            Path p = ctx.resolvePath(Values.text(a));
            try {
                b.append("<t:FileAttachment><t:Name>").append(x(p.getFileName().toString())).append("</t:Name><t:Content>")
                        .append(Base64.getEncoder().encodeToString(Files.readAllBytes(p))).append("</t:Content></t:FileAttachment>");
            } catch (NoSuchFileException e) { throw new ActionException("FILE.NOT_FOUND", "Attachment not found: " + p, "Check the path.");
            } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), "Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it.", true); }
        }
        return b.append("</t:Attachments>").toString();
    }

    private static String mailboxes(String element, List<String> addresses) {
        if (addresses.isEmpty()) return "";
        StringBuilder b = new StringBuilder("<t:").append(element).append(">");
        for (String a : addresses) b.append("<t:Mailbox><t:EmailAddress>").append(x(a)).append("</t:EmailAddress></t:Mailbox>");
        return b.append("</t:").append(element).append(">").toString();
    }

    private static String equalTo(String field, String value) { return compare("IsEqualTo", field, value); }

    private static String compare(String op, String field, String value) {
        return "<t:" + op + "><t:FieldURI FieldURI=\"" + field + "\"/><t:FieldURIOrConstant><t:Constant Value=\"" + x(value) + "\"/></t:FieldURIOrConstant></t:" + op + ">";
    }

    private static String utc(LocalDateTime t) { return t.withNano(0).toInstant(ZoneOffset.UTC).toString(); }

    private static List<String> parts(String text) {
        List<String> out = new ArrayList<>();
        if (text != null) for (String v : text.split(";")) if (!v.isBlank()) out.add(v.strip());
        return out;
    }

    /** Text for an XML element or attribute; characters XML 1.0 cannot hold are left out. */
    static String x(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&apos;");
                default -> { if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') b.append(c); }
            }
        }
        return b.toString();
    }

    static Element child(Element parent, String localName) {
        if (parent == null) return null;
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element e && localName.equals(e.getLocalName())) return e;
        return null;
    }

    static List<Element> children(Element parent) {
        List<Element> out = new ArrayList<>();
        if (parent != null) for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element e) out.add(e);
        return out;
    }

    private static Element first(Element parent) { List<Element> c = children(parent); return c.isEmpty() ? null : c.get(0); }

    private static String text(Element parent, String localName) { Element e = child(parent, localName); return e == null ? "" : e.getTextContent().strip(); }

    private static String attr(Element e, String name) { return e == null ? "" : e.getAttribute(name); }

    /** The address of a From or Sender element (Mailbox/EmailAddress), or its display name when there is no address. */
    private static String address(Element holder) {
        Element mb = child(holder, "Mailbox");
        if (mb == null) return "";
        String a = text(mb, "EmailAddress");
        return a.isEmpty() ? text(mb, "Name") : a;
    }

    private static List<Object> addresses(Element holder) {
        List<Object> out = new ArrayList<>();
        for (Element mb : children(holder)) { String a = text(mb, "EmailAddress"); out.add(a.isEmpty() ? text(mb, "Name") : a); }
        return out;
    }

    private static LocalDateTime time(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try { return LocalDateTime.ofInstant(Instant.parse(iso), ZoneOffset.UTC); }
        catch (DateTimeParseException e) {
            try { return LocalDateTime.ofInstant(java.time.OffsetDateTime.parse(iso).toInstant(), ZoneOffset.UTC); }
            catch (DateTimeParseException e2) { return null; }
        }
    }

    // ------------------------------------------------------------------ the SOAP call

    /**
     * Sends one EWS operation and returns its response message (e.g. FindItemResponseMessage) when Exchange reports
     * success; an error ResponseCode, a SOAP fault or an HTTP failure becomes an EXCHANGE.* error. The password never
     * reaches a message.
     */
    static Element call(ActionContext ctx, ExchangeSession s, String operation, String body) {
        String header = "<t:RequestServerVersion Version=\"Exchange2013\"/>" + (s.impersonate && s.otherMailbox()
                ? "<t:ExchangeImpersonation><t:ConnectingSID><t:PrimarySmtpAddress>" + x(s.mailbox) + "</t:PrimarySmtpAddress></t:ConnectingSID></t:ExchangeImpersonation>" : "");
        String envelope = "<?xml version=\"1.0\" encoding=\"utf-8\"?><soap:Envelope xmlns:soap=\"" + NS_SOAP + "\" xmlns:t=\"" + NS_TYPES + "\" xmlns:m=\"" + NS_MESSAGES + "\">"
                + "<soap:Header>" + header + "</soap:Header><soap:Body>" + body + "</soap:Body></soap:Envelope>";
        ctx.checkCancelled();
        int status;
        String answer;
        HttpURLConnection c;
        try { c = (HttpURLConnection) URI.create(s.url).toURL().openConnection(); }
        catch (IllegalArgumentException | IOException e) {
            throw new ActionException(P + ".BAD_URL", "Invalid EWS address '" + s.url + "'", "Use the server's EWS address, e.g. https://mail.example.com/EWS/Exchange.asmx.");
        }
        try {
            if (s.insecure && c instanceof HttpsURLConnection https) { https.setSSLSocketFactory(trustAll()); https.setHostnameVerifier((h, ss) -> true); }
            c.setAuthenticator(s.authenticator);
            c.setRequestMethod("POST");
            c.setConnectTimeout(20_000);
            c.setReadTimeout(s.timeoutSeconds * 1000);
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            c.setRequestProperty("Content-Type", "text/xml; charset=utf-8");
            c.setRequestProperty("Accept", "text/xml");
            c.setRequestProperty("SOAPAction", "\"" + NS_MESSAGES + "/" + operation + "\"");
            if (s.impersonate && s.otherMailbox()) c.setRequestProperty("X-AnchorMailbox", s.mailbox);
            if ("basic".equals(s.signIn))
                c.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString((s.user + ":" + s.password.reveal()).getBytes(StandardCharsets.UTF_8)));
            // not streamed: the body is buffered so it can be sent again after the NTLM challenge
            byte[] bytes = envelope.getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true);
            try (OutputStream o = c.getOutputStream()) { o.write(bytes); }
            status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            answer = "";
            if (in != null) try (InputStream i = in) { answer = new String(i.readAllBytes(), StandardCharsets.UTF_8); }
        } catch (SocketTimeoutException e) {
            throw new ActionException(P + ".TIMEOUT", "No answer from Exchange at " + s.url + " within " + s.timeoutSeconds + " s (" + operation + ")",
                    "Raise timeout_seconds on Connect, or check the server.", true);
        } catch (IOException e) {
            throw new ActionException(P + ".UNREACHABLE", "Cannot reach Exchange at " + s.url + ": " + NetErrors.reason(e, null),
                    "Check the EWS address, the network and the certificate (insecure_tls is for test systems only).", true);
        } finally { c.disconnect(); }
        return answer(ctx, s, operation, status, answer);
    }

    static Element answer(ActionContext ctx, ExchangeSession s, String operation, int status, String answer) {
        if (status == 401) throw new ActionException(P + ".AUTH_FAILED", "Exchange refused the sign-in of " + s.user + " (HTTP 401)",
                "Check the user (DOMAIN\\user or user@domain), the password in the vault and sign_in (ntlm or basic, as the server's EWS site allows).");
        if (status == 403) throw new ActionException(P + ".ACCESS_DENIED", "Exchange refused access for " + s.user + " (HTTP 403)",
                "Check the user may use EWS, and its rights on the mailbox.");
        if (status == 404) throw new ActionException(P + ".BAD_URL", "There is no EWS service at " + s.url + " (HTTP 404)",
                "Use the server's EWS address, usually https://<mail server>/EWS/Exchange.asmx.");
        if (status >= 300 && status < 400) throw new ActionException(P + ".BAD_URL", "Exchange at " + s.url + " redirects elsewhere (HTTP " + status + ")",
                "Use the final EWS address (often https:// instead of http://).");
        Document doc = null;
        if (answer.stripLeading().startsWith("<")) {
            try { doc = XmlSupport.parse(answer, true); } catch (ActionException ignored) { }
        }
        Element bodyEl = doc == null || !"Envelope".equals(doc.getDocumentElement().getLocalName()) ? null : child(doc.getDocumentElement(), "Body");
        if (bodyEl == null) {
            if (status >= 500) throw new ActionException(P + ".HTTP_ERROR", "Exchange answered HTTP " + status + " to " + operation + (answer.isBlank() ? "" : ": " + HttpJson.snippet(answer)),
                    "Try again later, or ask the Exchange administrator to check the server.", true);
            throw new ActionException(P + ".BAD_RESPONSE", "The answer from " + s.url + " is not an EWS answer (HTTP " + status + "; often a proxy or sign-in page)",
                    "Check the EWS address ends with /EWS/Exchange.asmx and that no proxy is in the way.");
        }
        Element fault = child(bodyEl, "Fault");
        if (fault != null) {
            String code = descendantText(fault, "ResponseCode");
            String why = descendantText(fault, "Message");
            if (why.isEmpty()) why = text(fault, "faultstring");
            throw error(code.isEmpty() ? "SOAP fault" : code, why, operation);
        }
        Element response = first(bodyEl);
        Element msg = first(child(response, "ResponseMessages"));
        if (msg == null) throw new ActionException(P + ".BAD_RESPONSE", "Exchange's answer to " + operation + " has no response message", "The service answered in an unexpected way; try again, and if it keeps happening check the service's status page.");
        String cls = msg.getAttribute("ResponseClass");
        if ("Error".equals(cls)) throw error(text(msg, "ResponseCode"), text(msg, "MessageText"), operation);
        if ("Warning".equals(cls)) ctx.warn("Exchange warned about " + operation + ": " + text(msg, "ResponseCode") + " " + text(msg, "MessageText"));
        return msg;
    }

    private static String descendantText(Element e, String localName) {
        org.w3c.dom.NodeList all = e.getElementsByTagNameNS("*", localName);
        return all.getLength() == 0 ? "" : all.item(0).getTextContent().strip();
    }

    /** EWS response codes as EXCHANGE.* errors; the code itself stays in the message for the administrator. */
    static ActionException error(String code, String text, String operation) {
        String what = "Exchange could not do " + operation + ": " + code + (text == null || text.isBlank() ? "" : " (" + text + ")");
        Map<String, Object> details = Map.of("response_code", code);
        return switch (code) {
            case "ErrorItemNotFound", "ErrorInvalidIdMalformed", "ErrorInvalidIdEmpty", "ErrorInvalidIdNotAnItemAttachmentId", "ErrorInvalidIdMalformedEwsLegacyIdFormat",
                 "ErrorAttachmentNotFound" ->
                    new ActionException(P + ".NOT_FOUND", what, "The email may have been moved (it then has a new id) or deleted; take the id from Find emails.", false, details);
            case "ErrorFolderNotFound" -> new ActionException(P + ".NO_FOLDER", what, "Use Get folders to see the exact names.", false, details);
            case "ErrorNonExistentMailbox", "ErrorMailboxStoreUnavailable", "ErrorMailboxMoveInProgress" ->
                    new ActionException(P + ".NO_MAILBOX", what, "Check the mailbox address on Connect.", code.equals("ErrorMailboxMoveInProgress") || code.equals("ErrorMailboxStoreUnavailable"), details);
            case "ErrorAccessDenied", "ErrorImpersonateUserDenied", "ErrorImpersonationDenied", "ErrorSendAsDenied", "ErrorDelegateNoUser", "ErrorNoPublicFolderReplicaAvailable" ->
                    new ActionException(P + ".ACCESS_DENIED", what, "Give the user rights on the mailbox (full access, Send As, or the ApplicationImpersonation role).", false, details);
            case "ErrorInvalidRecipients", "ErrorInvalidSmtpAddress", "ErrorMissingRecipients", "ErrorInvalidEmailAddress" ->
                    new ActionException(P + ".BAD_ADDRESS", what, "Check the recipient addresses.", false, details);
            case "ErrorMessageSizeExceeded", "ErrorQuotaExceeded" ->
                    new ActionException(P + ".TOO_LARGE", what, "Send smaller attachments, or ask for a larger mailbox or message limit.", false, details);
            case "ErrorServerBusy", "ErrorTooManyObjectsOpened", "ErrorInternalServerTransientError" ->
                    new ActionException(P + ".SERVER_BUSY", what, "Exchange is throttling or busy; try again later.", true, details);
            case "ErrorTimeoutExpired" -> new ActionException(P + ".TIMEOUT", what, "Try again, or narrow the search.", true, details);
            default -> new ActionException(P + ".SERVER_ERROR", what, "Check the inputs; the response code tells the Exchange administrator what went wrong.", false, details);
        };
    }

    private static SSLSocketFactory trustAll() {
        try {
            SSLContext c = SSLContext.getInstance("TLS");
            c.init(null, new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String a) { }
                public void checkServerTrusted(X509Certificate[] chain, String a) { }
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, null);
            return c.getSocketFactory();
        } catch (java.security.GeneralSecurityException e) {
            throw new ActionException(P + ".TLS_SETUP_FAILED", "Cannot set up TLS: " + e.getMessage(), "Check the runner's Java security settings and the server's certificate; ask IT whether TLS inspection is in the way.");
        }
    }
}
