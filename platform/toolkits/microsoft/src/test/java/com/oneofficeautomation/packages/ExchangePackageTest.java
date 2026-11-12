package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.SecureString;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exchange on-premises (EWS) against a small stand-in server: it checks the SOAP operation and request XML each action
 * sends and answers with EWS-shaped XML (FindItem, GetItem, CreateItem, SendItem, MoveItem, UpdateItem, DeleteItem,
 * GetAttachment, FindFolder, CreateFolder), a 401 for a wrong password and error ResponseCodes. NTLM sign-in uses the
 * NTLMv2-checking server of the REST tests.
 */
class ExchangePackageTest {
    static final String PW = "Ex-S3cret!";
    static final String ID1 = "AAMkAGI2TG93AAA=", ID2 = "AAMkAGI2TG94AAA=";
    static final String BAD_ID = "AAMkMissingAAA=";

    final ExchangePackage ex = new ExchangePackage();
    final List<String> ops = Collections.synchronizedList(new ArrayList<>());
    final List<String> bodies = Collections.synchronizedList(new ArrayList<>());
    final Map<String, Object> sessions = new HashMap<>();
    final List<String> logs = Collections.synchronizedList(new ArrayList<>());
    Path work;

    ActionContext ctx() throws Exception {
        if (work == null) work = Files.createTempDirectory("ooa-ews");
        return new ActionContext("run", "s", "default", work, sessions, new HashMap<>(), new AtomicBoolean(), () -> List.of(PW), (l, m) -> logs.add(l + " " + m));
    }

    static String envelope(String body) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<s:Header><h:ServerVersionInfo MajorVersion=\"15\" MinorVersion=\"2\" xmlns:h=\"http://schemas.microsoft.com/exchange/services/2006/types\"/></s:Header>"
                + "<s:Body xmlns:m=\"http://schemas.microsoft.com/exchange/services/2006/messages\" xmlns:t=\"http://schemas.microsoft.com/exchange/services/2006/types\">"
                + body + "</s:Body></s:Envelope>";
    }

    static String ok(String op, String inner) {
        return envelope("<m:" + op + "Response><m:ResponseMessages><m:" + op + "ResponseMessage ResponseClass=\"Success\"><m:ResponseCode>NoError</m:ResponseCode>"
                + inner + "</m:" + op + "ResponseMessage></m:ResponseMessages></m:" + op + "Response>");
    }

    static String fail(String op, String code, String text) {
        return envelope("<m:" + op + "Response><m:ResponseMessages><m:" + op + "ResponseMessage ResponseClass=\"Error\"><m:MessageText>" + text
                + "</m:MessageText><m:ResponseCode>" + code + "</m:ResponseCode><m:DescriptiveLinkKey>0</m:DescriptiveLinkKey></m:" + op + "ResponseMessage></m:ResponseMessages></m:" + op + "Response>");
    }

    static String message(String id, String subject, String from, String received, boolean read, boolean atts) {
        return "<t:Message><t:ItemId Id=\"" + id + "\" ChangeKey=\"CQAAABYA\"/><t:Subject>" + subject + "</t:Subject><t:DateTimeReceived>" + received + "</t:DateTimeReceived>"
                + "<t:HasAttachments>" + atts + "</t:HasAttachments><t:From><t:Mailbox><t:Name>Sender</t:Name><t:EmailAddress>" + from
                + "</t:EmailAddress><t:RoutingType>SMTP</t:RoutingType></t:Mailbox></t:From><t:IsRead>" + read + "</t:IsRead></t:Message>";
    }

    /** The stand-in EWS: Basic sign-in with bot / PW, answers per SOAP operation. */
    HttpServer ews() throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String expected = "Basic " + Base64.getEncoder().encodeToString(("ACME\\bot:" + PW).getBytes(StandardCharsets.UTF_8));
        s.createContext("/EWS/Exchange.asmx", h -> {
            String body = new String(h.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String action = Optional.ofNullable(h.getRequestHeaders().getFirst("SOAPAction")).orElse("");
            String op = action.replaceAll(".*/", "").replace("\"", "");
            int status = 200;
            String out;
            if (!expected.equals(h.getRequestHeaders().getFirst("Authorization"))) {
                status = 401; out = "";
                h.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"mail.acme.test\"");
            } else {
                ops.add(op); bodies.add(body);
                assertTrue(body.contains("<t:RequestServerVersion Version=\"Exchange2013\"/>"), body);
                assertTrue(body.contains("<soap:Body><m:" + op), op + " body: " + body);
                out = switch (op) {
                    case "GetFolder" -> ok("GetFolder", "<m:Folders><t:Folder><t:FolderId Id=\"INBOXID\"/></t:Folder></m:Folders>");
                    case "FindItem" -> ok("FindItem", "<m:RootFolder IndexedPagingOffset=\"2\" TotalItemsInView=\"2\" IncludesLastItemInRange=\"true\"><t:Items>"
                            + message(ID1, "Invoice 42", "billing@vendor.test", "2026-11-03T09:15:00Z", false, true)
                            + message(ID2, "Lunch", "friend@other.test", "2026-11-02T12:00:00Z", true, false) + "</t:Items></m:RootFolder>");
                    case "GetItem" -> body.contains(BAD_ID) ? fail("GetItem", "ErrorItemNotFound", "The specified object was not found in the store.")
                            : body.contains("DRAFT1") ? ok("GetItem", "<m:Items><t:Message><t:ItemId Id=\"DRAFT1\" ChangeKey=\"CK9\"/>"
                            + "<t:ToRecipients><t:Mailbox><t:EmailAddress>billing@vendor.test</t:EmailAddress></t:Mailbox><t:Mailbox><t:EmailAddress>team@acme.test</t:EmailAddress></t:Mailbox></t:ToRecipients>"
                            + "<t:CcRecipients><t:Mailbox><t:EmailAddress>BILLING@vendor.test</t:EmailAddress></t:Mailbox></t:CcRecipients></t:Message></m:Items>")
                            : body.contains("IncludeMimeContent") ? ok("GetItem", "<m:Items><t:Message><t:MimeContent CharacterSet=\"UTF-8\">"
                            + Base64.getEncoder().encodeToString("Subject: Invoice 42\r\n\r\nPlease pay".getBytes(StandardCharsets.UTF_8))
                            + "</t:MimeContent><t:ItemId Id=\"" + ID1 + "\" ChangeKey=\"CQAAABYA\"/><t:Subject>Invoice 42: Q4/2026</t:Subject></t:Message></m:Items>")
                            : ok("GetItem", "<m:Items><t:Message><t:ItemId Id=\"" + ID1 + "\" ChangeKey=\"CQAAABYA\"/><t:Subject>Invoice 42</t:Subject>"
                            + "<t:Body BodyType=\"HTML\">&lt;p&gt;Please pay&lt;/p&gt;</t:Body><t:TextBody BodyType=\"Text\">Please pay</t:TextBody>"
                            + "<t:Attachments><t:FileAttachment><t:AttachmentId Id=\"ATT1\"/><t:Name>inv-42.pdf</t:Name><t:ContentType>application/pdf</t:ContentType><t:Size>9</t:Size><t:IsInline>false</t:IsInline></t:FileAttachment>"
                            + "<t:FileAttachment><t:AttachmentId Id=\"ATT2\"/><t:Name>logo.png</t:Name><t:IsInline>true</t:IsInline></t:FileAttachment>"
                            + "<t:ItemAttachment><t:AttachmentId Id=\"ATT3\"/><t:Name>Earlier mail</t:Name></t:ItemAttachment></t:Attachments>"
                            + "<t:DateTimeReceived>2026-11-03T09:15:00Z</t:DateTimeReceived><t:Size>4821</t:Size><t:Importance>High</t:Importance>"
                            + "<t:DateTimeSent>2026-11-03T09:14:58Z</t:DateTimeSent><t:ConversationId Id=\"CONV1\"/><t:Flag><t:FlagStatus>Flagged</t:FlagStatus></t:Flag>"
                            + "<t:ToRecipients><t:Mailbox><t:Name>Bot</t:Name><t:EmailAddress>bot@acme.test</t:EmailAddress></t:Mailbox></t:ToRecipients>"
                            + "<t:CcRecipients><t:Mailbox><t:EmailAddress>ap@acme.test</t:EmailAddress></t:Mailbox></t:CcRecipients>"
                            + "<t:From><t:Mailbox><t:Name>Billing</t:Name><t:EmailAddress>billing@vendor.test</t:EmailAddress></t:Mailbox></t:From>"
                            + "<t:InternetMessageId>&lt;42@vendor.test&gt;</t:InternetMessageId><t:IsRead>false</t:IsRead></t:Message></m:Items>");
                    case "GetAttachment" -> ok("GetAttachment", "<m:Attachments><t:FileAttachment><t:AttachmentId Id=\"ATT1\"/><t:Name>inv-42.pdf</t:Name><t:Content>"
                            + Base64.getEncoder().encodeToString("%PDF-1.7".getBytes(StandardCharsets.US_ASCII)) + "</t:Content></t:FileAttachment></m:Attachments>");
                    case "UpdateItem" -> ok("UpdateItem", "<m:Items><t:Message><t:ItemId Id=\"" + ID1 + "\" ChangeKey=\"CQAAABYB\"/></t:Message></m:Items>");
                    case "CreateItem" -> body.contains("SaveOnly") ? ok("CreateItem", "<m:Items><t:Message><t:ItemId Id=\"DRAFT1\" ChangeKey=\"CK9\"/></t:Message></m:Items>")
                            : body.contains("nobody@") ? fail("CreateItem", "ErrorInvalidRecipients", "At least one recipient isn't valid.")
                            : ok("CreateItem", "<m:Items/>");
                    case "SendItem" -> ok("SendItem", "");
                    case "CreateAttachment" -> ok("CreateAttachment", "<m:Attachments><t:FileAttachment><t:AttachmentId Id=\"A1\" RootItemId=\"DRAFT1\" RootItemChangeKey=\"CK10\"/>"
                            + "</t:FileAttachment></m:Attachments>");
                    case "FindFolder" -> body.contains("Traversal=\"Deep\"")
                            ? ok("FindFolder", "<m:RootFolder TotalItemsInView=\"3\" IncludesLastItemInRange=\"true\"><t:Folders>"
                                + "<t:Folder><t:FolderId Id=\"INBOXID\"/><t:ParentFolderId Id=\"ROOTID\"/><t:DisplayName>Inbox</t:DisplayName></t:Folder>"
                                + "<t:Folder><t:FolderId Id=\"INVID\"/><t:ParentFolderId Id=\"INBOXID\"/><t:DisplayName>Invoices</t:DisplayName></t:Folder>"
                                + "<t:Folder><t:FolderId Id=\"SENTID\"/><t:ParentFolderId Id=\"ROOTID\"/><t:DisplayName>Sent Items</t:DisplayName></t:Folder></t:Folders></m:RootFolder>")
                            : ok("FindFolder", "<m:RootFolder TotalItemsInView=\"1\" IncludesLastItemInRange=\"true\"><t:Folders>"
                                + "<t:Folder><t:FolderId Id=\"INVID\"/><t:DisplayName>Invoices</t:DisplayName></t:Folder></t:Folders></m:RootFolder>");
                    case "CreateFolder" -> ok("CreateFolder", "<m:Folders><t:Folder><t:FolderId Id=\"NEWFOLDER\"/></t:Folder></m:Folders>");
                    case "MoveItem" -> ok("MoveItem", "<m:Items><t:Message><t:ItemId Id=\"MOVED1\" ChangeKey=\"CK2\"/></t:Message></m:Items>");
                    case "DeleteItem" -> ok("DeleteItem", "");
                    default -> envelope("<s:Fault><faultcode>s:Client</faultcode><faultstring>The request failed schema validation.</faultstring><detail>"
                            + "<e:ResponseCode xmlns:e=\"http://schemas.microsoft.com/exchange/services/2006/errors\">ErrorSchemaValidation</e:ResponseCode>"
                            + "<e:Message xmlns:e=\"http://schemas.microsoft.com/exchange/services/2006/errors\">The request failed schema validation.</e:Message></detail></s:Fault>");
                };
                if (out.contains("<s:Fault>")) status = 500;
            }
            byte[] b = out.getBytes(StandardCharsets.UTF_8);
            h.getResponseHeaders().set("Content-Type", "text/xml; charset=utf-8");
            h.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
            if (b.length > 0) h.getResponseBody().write(b);
            h.close();
        });
        s.start();
        return s;
    }

    static String url(HttpServer s) { return "http://127.0.0.1:" + s.getAddress().getPort() + "/EWS/Exchange.asmx"; }

    void connect(HttpServer s, String mailbox, boolean impersonate) throws Exception {
        ex.connect(ctx(), url(s), "ACME\\bot", new SecureString(PW, "test/ews/password"), "basic", mailbox, impersonate, 30, false, "exchange");
    }

    @Test @SuppressWarnings("unchecked")
    void findReadSaveAndChange() throws Exception {
        HttpServer s = ews();
        try {
            connect(s, null, false);
            assertEquals(List.of("GetFolder"), ops);
            assertTrue(bodies.get(0).contains("<t:DistinguishedFolderId Id=\"inbox\"></t:DistinguishedFolderId>"), bodies.get(0));

            // Find emails: subject and dates go to the server, the sender is matched here
            Map<String, Object> r = ex.list(ctx(), "Inbox", true, "vendor", "Invoice;Bill", LocalDateTime.of(2026, 11, 1, 0, 0), 10, null, false, false, false, "exchange");
            assertEquals(new BigDecimal(1), r.get("count"));
            String find = bodies.get(1);
            assertTrue(find.contains("<t:FieldURI FieldURI=\"message:IsRead\"/><t:FieldURIOrConstant><t:Constant Value=\"false\"/>"), find);
            assertTrue(find.contains("<t:Or><t:Contains ContainmentMode=\"Substring\" ContainmentComparison=\"IgnoreCase\"><t:FieldURI FieldURI=\"item:Subject\"/><t:Constant Value=\"Invoice\"/>"), find);
            assertTrue(find.contains("<t:IsGreaterThanOrEqualTo><t:FieldURI FieldURI=\"item:DateTimeReceived\"/><t:FieldURIOrConstant><t:Constant Value=\"2026-11-01T00:00:00Z\"/>"), find);
            assertTrue(find.contains("<t:FieldOrder Order=\"Descending\">") && find.contains("<m:ParentFolderIds><t:DistinguishedFolderId Id=\"inbox\">"), find);
            Map<String, Object> table = (Map<String, Object>) r.get("table");
            assertEquals(ExchangePackage.SUMMARY_COLUMNS, table.get("columns"));
            List<Object> row = (List<Object>) ((List<Object>) table.get("rows")).get(0);
            assertEquals(List.of(ID1, "billing@vendor.test", "Invoice 42", LocalDateTime.of(2026, 11, 3, 9, 15), true, true), row);

            // the loop yields the same records
            List<Object> loop = new ArrayList<>();
            ex.emails(ctx(), "inbox", false, null, null, null, 100, "exchange").forEach(loop::add);
            assertEquals(2, loop.size());
            assertEquals("Lunch", ((Map<String, Object>) loop.get(1)).get("subject"));

            // Read email, marking it read
            ops.clear(); bodies.clear();
            Map<String, Object> e = (Map<String, Object>) ex.get(ctx(), ID1, true, "exchange").get("email");
            assertEquals("billing@vendor.test", e.get("from"));
            assertEquals(List.of("bot@acme.test"), e.get("to"));
            assertEquals(List.of("ap@acme.test"), e.get("cc"));
            assertEquals("Please pay", e.get("text"));
            assertEquals("<p>Please pay</p>", e.get("html"));
            assertEquals(List.of("inv-42.pdf", "logo.png", "Earlier mail"), e.get("attachments"));
            assertEquals("<42@vendor.test>", e.get("message_id"));
            assertEquals("high", e.get("importance"));
            assertEquals(true, e.get("flagged"));
            assertEquals(new BigDecimal(4821), e.get("size"));
            assertEquals(List.of("GetItem", "UpdateItem"), ops);
            assertTrue(bodies.get(1).contains("<t:ItemId Id=\"" + ID1 + "\"/><t:Updates><t:SetItemField><t:FieldURI FieldURI=\"message:IsRead\"/><t:Message><t:IsRead>true</t:IsRead>"), bodies.get(1));

            // Save attachments: only file attachments, inline ones left out when asked
            ops.clear(); bodies.clear();
            List<Object> files = (List<Object>) ex.saveAttachments(ctx(), ID1, "att", "*.pdf", false, false, "exchange").get("files");
            assertEquals(1, files.size());
            assertEquals("%PDF-1.7", Files.readString(Path.of((String) files.get(0))));
            assertEquals(List.of("GetItem", "GetAttachment"), ops);
            assertTrue(bodies.get(1).contains("<t:AttachmentId Id=\"ATT1\"/>"));
            // the same name again gets a number
            List<Object> again = (List<Object>) ex.saveAttachments(ctx(), ID1, "att", "*.pdf", false, true, "exchange").get("files");
            assertTrue(((String) again.get(0)).endsWith("inv-42 (2).pdf"), again.toString());

            // Mark unread and flag; move to a path (found, then created); delete to Deleted Items
            ops.clear(); bodies.clear();
            ex.mark(ctx(), ID1, false, true, "exchange");
            assertTrue(bodies.get(0).contains("<t:IsRead>false</t:IsRead>") && bodies.get(0).contains("<t:FlagStatus>Flagged</t:FlagStatus>"), bodies.get(0));
            assertEquals("MOVED1", ex.move(ctx(), ID1, "Inbox/Invoices", false, "exchange").get("new_id"));
            assertTrue(bodies.get(2).contains("<m:ToFolderId><t:FolderId Id=\"INVID\"/></m:ToFolderId>") && bodies.get(2).contains("<m:ReturnNewItemIds>true</m:ReturnNewItemIds>"), bodies.get(2));
            ex.move(ctx(), ID2, "Inbox/Invoices/2026", true, "exchange");
            assertTrue(ops.contains("CreateFolder"), ops.toString());
            assertTrue(bodies.stream().anyMatch(b -> b.contains("<t:DisplayName>2026</t:DisplayName>") && b.contains("<m:ParentFolderId><t:FolderId Id=\"INVID\"/>")), bodies.toString());
            assertEquals("NO_FOLDER", code(() -> ex.move(ctx(), ID2, "Inbox/Nowhere", false, "exchange")));
            bodies.clear();
            ex.delete(ctx(), ID2, false, "exchange");
            assertTrue(bodies.get(0).contains("<m:DeleteItem DeleteType=\"MoveToDeletedItems\">"), bodies.get(0));
            ex.delete(ctx(), ID2, true, "exchange");
            assertTrue(bodies.get(1).contains("DeleteType=\"SoftDelete\""), bodies.get(1));

            // folders as paths
            assertEquals(List.of("Inbox", "Inbox/Invoices", "Sent Items"), ex.getFolders(ctx(), "exchange").get("folders"));

            // an EWS error ResponseCode becomes EXCHANGE.NOT_FOUND, with the code in the details
            ActionException nf = assertThrows(ActionException.class, () -> ex.get(ctx(), BAD_ID, false, "exchange"));
            assertEquals("EXCHANGE.NOT_FOUND", nf.code());
            assertTrue(nf.getMessage().contains("ErrorItemNotFound"), nf.getMessage());

            ex.disconnect(ctx(), "exchange");
            assertFalse(sessions.containsKey("exchange"));
        } finally { s.stop(0); }
    }

    @Test
    void sendReplyForward() throws Exception {
        HttpServer s = ews();
        try {
            connect(s, null, false);
            ops.clear(); bodies.clear();
            ex.send(ctx(), "a@x.test; b@x.test", "Report <Q4> & more", "<b>Hi</b>", true, "c@x.test", null, null, null, null, "high", true, true, "exchange");
            assertEquals(List.of("CreateItem"), ops);
            String b = bodies.get(0);
            assertTrue(b.contains("<m:CreateItem MessageDisposition=\"SendAndSaveCopy\"><m:SavedItemFolderId><t:DistinguishedFolderId Id=\"sentitems\">"), b);
            assertTrue(b.contains("<t:Subject>Report &lt;Q4&gt; &amp; more</t:Subject><t:Body BodyType=\"HTML\">&lt;b&gt;Hi&lt;/b&gt;</t:Body><t:Importance>High</t:Importance>"), b);
            assertTrue(b.contains("<t:ToRecipients><t:Mailbox><t:EmailAddress>a@x.test</t:EmailAddress></t:Mailbox><t:Mailbox><t:EmailAddress>b@x.test</t:EmailAddress></t:Mailbox></t:ToRecipients>"
                    + "<t:CcRecipients><t:Mailbox><t:EmailAddress>c@x.test</t:EmailAddress></t:Mailbox></t:CcRecipients><t:IsReadReceiptRequested>true</t:IsReadReceiptRequested>"), b);

            // with an attachment: saved as a draft, then sent with its change key
            Files.writeString(ctx().workDir().resolve("inv.txt"), "INV-1");
            ops.clear(); bodies.clear();
            ex.send(ctx(), List.of("a@x.test"), "With file", "see file", false, null, null, List.of("inv.txt"), null, null, "normal", false, true, "exchange");
            assertEquals(List.of("CreateItem", "SendItem"), ops);
            assertTrue(bodies.get(0).contains("MessageDisposition=\"SaveOnly\"") && bodies.get(0).contains("<t:FileAttachment><t:Name>inv.txt</t:Name><t:Content>"
                    + Base64.getEncoder().encodeToString("INV-1".getBytes(StandardCharsets.UTF_8)) + "</t:Content>"), bodies.get(0));
            assertTrue(bodies.get(1).contains("<m:SendItem SaveItemToFolder=\"true\"><m:ItemIds><t:ItemId Id=\"DRAFT1\" ChangeKey=\"CK9\"/>"), bodies.get(1));

            // reply, reply all and forward point at the original with its change key
            ops.clear(); bodies.clear();
            ex.reply(ctx(), ID1, "Thanks", false, null, null, "exchange");
            ex.replyAll(ctx(), ID1, "Thanks all", false, null, null, "exchange");
            ex.forward(ctx(), ID1, "ap@acme.test", "<i>FYI</i>", true, null, null, "exchange");
            assertEquals(List.of("GetItem", "CreateItem", "GetItem", "CreateItem", "GetItem", "CreateItem"), ops);
            assertTrue(bodies.get(1).contains("<t:ReplyToItem><t:ReferenceItemId Id=\"" + ID1 + "\" ChangeKey=\"CQAAABYA\"/><t:NewBodyContent BodyType=\"Text\">Thanks</t:NewBodyContent></t:ReplyToItem>"), bodies.get(1));
            assertTrue(bodies.get(3).contains("<t:ReplyAllToItem>"), bodies.get(3));
            assertTrue(bodies.get(5).contains("<t:ForwardItem><t:ToRecipients><t:Mailbox><t:EmailAddress>ap@acme.test</t:EmailAddress></t:Mailbox></t:ToRecipients><t:ReferenceItemId")
                    && bodies.get(5).contains("<t:NewBodyContent BodyType=\"HTML\">&lt;i&gt;FYI&lt;/i&gt;"), bodies.get(5));

            // a refused recipient is EXCHANGE.BAD_ADDRESS; no recipient at all is refused before sending
            assertEquals("BAD_ADDRESS", code(() -> ex.send(ctx(), "nobody@x.test", "x", "", false, null, null, null, null, null, "normal", false, true, "exchange")));
            assertEquals("BAD_ADDRESS", code(() -> ex.send(ctx(), "", "x", "", false, null, null, null, null, null, "normal", false, true, "exchange")));
            assertEquals("FILE.NOT_FOUND", assertThrows(ActionException.class, () -> ex.send(ctx(), "a@x.test", "x", "", false, null, null, List.of("missing.pdf"),
                    null, null, "normal", false, true, "exchange")).code());
        } finally { s.stop(0); }
    }

    /** A6 (command review, batch 7): what the other mail services already had. */
    @Test
    void repliesWithFilesWithoutTheSenderForwardAsAttachmentAndSaveAsEml() throws Exception {
        HttpServer s = ews();
        try {
            connect(s, null, false);
            Files.writeString(ctx().workDir().resolve("answer.txt"), "OK");
            // a reply with a file: a draft, the file added to it, then sent with the change key the file gave it
            ops.clear(); bodies.clear();
            ex.reply(ctx(), ID1, "See attached", false, null, null, "exchange", List.of("answer.txt"));
            assertEquals(List.of("GetItem", "CreateItem", "CreateAttachment", "SendItem"), ops);
            assertTrue(bodies.get(1).contains("MessageDisposition=\"SaveOnly\"") && bodies.get(1).contains("<t:ReplyToItem>"), bodies.get(1));
            assertTrue(bodies.get(2).contains("<m:ParentItemId Id=\"DRAFT1\" ChangeKey=\"CK9\"/><m:Attachments><t:FileAttachment><t:Name>answer.txt</t:Name>"), bodies.get(2));
            assertTrue(bodies.get(3).contains("<t:ItemId Id=\"DRAFT1\" ChangeKey=\"CK10\"/>"), bodies.get(3));

            // reply all without the sender: the draft's recipients are set again without it (any case), then sent
            ops.clear(); bodies.clear();
            ex.replyAll(ctx(), ID1, "Team only", false, null, null, "exchange", null, true);
            assertEquals(List.of("GetItem", "CreateItem", "GetItem", "UpdateItem", "SendItem"), ops);
            String upd = bodies.get(3);
            assertTrue(upd.contains("<t:ToRecipients><t:Mailbox><t:EmailAddress>team@acme.test</t:EmailAddress></t:Mailbox></t:ToRecipients>") && !upd.contains("billing@"), upd);
            assertTrue(upd.contains("<t:DeleteItemField><t:FieldURI FieldURI=\"message:CcRecipients\"/></t:DeleteItemField>"), upd);

            // forward as attachment: a new email carrying the original's MIME content as an .eml file
            ops.clear(); bodies.clear();
            ex.forward(ctx(), ID1, "ap@acme.test", "FYI", false, null, null, "exchange", null, true);
            assertEquals(List.of("GetItem", "CreateItem", "SendItem"), ops);
            assertTrue(bodies.get(0).contains("<t:IncludeMimeContent>true</t:IncludeMimeContent>"), bodies.get(0));
            assertTrue(bodies.get(1).contains("<t:Subject>FW: Invoice 42: Q4/2026</t:Subject>") && bodies.get(1).contains("<t:Name>Invoice 42_ Q4_2026.eml</t:Name>"), bodies.get(1));

            // save as .eml: into a folder, named after the subject
            Path out = ctx().workDir().resolve("mails");
            Files.createDirectories(out);
            Path saved = Path.of((String) ex.saveEmailAsEml(ctx(), ID1, "mails", true, "exchange").get("path"));
            assertEquals(out.resolve("Invoice 42_ Q4_2026.eml"), saved);
            assertEquals("Subject: Invoice 42\r\n\r\nPlease pay", Files.readString(saved));
            assertEquals("FILE.EXISTS", assertThrows(ActionException.class, () -> ex.saveEmailAsEml(ctx(), ID1, saved.toString(), false, "exchange")).code());

            // the body is searched by the server
            ops.clear(); bodies.clear();
            ex.list(ctx(), "inbox", false, null, null, null, 10, null, false, false, false, "exchange", "overdue;reminder");
            assertTrue(bodies.get(bodies.size() - 1).contains("<t:Or><t:Contains ContainmentMode=\"Substring\" ContainmentComparison=\"IgnoreCase\"><t:FieldURI FieldURI=\"item:Body\"/>"
                    + "<t:Constant Value=\"overdue\"/>"), bodies.get(bodies.size() - 1));
        } finally { s.stop(0); }
    }

    @Test
    void otherMailboxAsDelegateOrByImpersonation() throws Exception {
        HttpServer s = ews();
        try {
            connect(s, "invoices@acme.test", false);
            assertTrue(bodies.get(0).contains("<t:DistinguishedFolderId Id=\"inbox\"><t:Mailbox><t:EmailAddress>invoices@acme.test</t:EmailAddress></t:Mailbox></t:DistinguishedFolderId>"), bodies.get(0));
            ex.send(ctx(), "a@x.test", "x", "", false, null, null, null, null, null, "normal", false, true, "exchange");
            assertTrue(bodies.get(1).contains("<t:From><t:Mailbox><t:EmailAddress>invoices@acme.test</t:EmailAddress></t:Mailbox></t:From>"), bodies.get(1));
            bodies.clear();
            connect(s, "invoices@acme.test", true);
            assertTrue(bodies.get(0).contains("<t:ExchangeImpersonation><t:ConnectingSID><t:PrimarySmtpAddress>invoices@acme.test</t:PrimarySmtpAddress>"), bodies.get(0));
            assertTrue(bodies.get(0).contains("<t:DistinguishedFolderId Id=\"inbox\"></t:DistinguishedFolderId>"), bodies.get(0));
        } finally { s.stop(0); }
    }

    @Test
    void signInAndServerErrors() throws Exception {
        HttpServer s = ews();
        try {
            // a wrong password: HTTP 401, and the password is in no message
            ActionException e = assertThrows(ActionException.class, () -> ex.connect(ctx(), url(s), "ACME\\bot", new SecureString("wrong-pw", "test/ews/password"),
                    "basic", null, false, 30, false, "exchange"));
            assertEquals("EXCHANGE.AUTH_FAILED", e.code());
            assertFalse(e.getMessage().contains("wrong-pw") || String.valueOf(e.suggestedFix()).contains("wrong-pw"));
            assertFalse(sessions.containsKey("exchange"));

            // a password typed as plain text is not used; a bad address or sign-in is refused before anything is sent
            assertEquals("NO_SECRET", code(() -> ex.connect(ctx(), url(s), "ACME\\bot", PW, "basic", null, false, 30, false, "exchange")));
            assertEquals("BAD_URL", code(() -> ex.connect(ctx(), "mail.acme.test/EWS", "ACME\\bot", new SecureString(PW, "r"), "ntlm", null, false, 30, false, "exchange")));
            assertEquals("NO_USER", code(() -> ex.connect(ctx(), url(s), " ", new SecureString(PW, "r"), "ntlm", null, false, 30, false, "exchange")));
            assertEquals("BAD_INPUT", code(() -> ex.connect(ctx(), url(s), "bot", new SecureString(PW, "r"), "kerberos", null, false, 30, false, "exchange")));
            assertEquals("BAD_INPUT", code(() -> ex.connect(ctx(), url(s), "bot", new SecureString(PW, "r"), "ntlm", null, true, 30, false, "exchange")));
            assertTrue(ops.isEmpty(), ops.toString());

            // a path that is not EWS
            assertEquals("BAD_URL", code(() -> ex.connect(ctx(), url(s).replace("/EWS/Exchange.asmx", "/owa"), "ACME\\bot", new SecureString(PW, "r"), "basic", null, false, 30, false, "exchange")));
            // nothing listening: retryable
            ActionException down = assertThrows(ActionException.class, () -> ex.connect(ctx(), "http://127.0.0.1:1/EWS/Exchange.asmx", "bot", new SecureString(PW, "r"), "basic", null, false, 5, false, "exchange"));
            assertEquals("EXCHANGE.UNREACHABLE", down.code());
            assertTrue(down.retryable());
        } finally { s.stop(0); }
        // a SOAP fault and response codes map to EXCHANGE.* (the session is only used for its address and user)
        ExchangePackage.ExchangeSession es = new ExchangePackage.ExchangeSession("https://mail.acme.test/EWS/Exchange.asmx", "bot", new SecureString(PW, "r"), "ntlm", null, false, false, 30);
        ActionException fault = assertThrows(ActionException.class, () -> ExchangePackage.answer(ctx(), es, "FindItem", 500,
                envelope("<s:Fault><faultcode>s:Client</faultcode><faultstring>bad</faultstring><detail><e:ResponseCode xmlns:e=\"urn:e\">ErrorSchemaValidation</e:ResponseCode></detail></s:Fault>")));
        assertEquals("EXCHANGE.SERVER_ERROR", fault.code());
        assertTrue(fault.getMessage().contains("ErrorSchemaValidation"));
        ActionException busy = assertThrows(ActionException.class, () -> ExchangePackage.answer(ctx(), es, "FindItem", 200, fail("FindItem", "ErrorServerBusy", "busy")));
        assertEquals("EXCHANGE.SERVER_BUSY", busy.code());
        assertTrue(busy.retryable());
        assertEquals("EXCHANGE.ACCESS_DENIED", assertThrows(ActionException.class, () -> ExchangePackage.answer(ctx(), es, "GetFolder", 200,
                fail("GetFolder", "ErrorImpersonateUserDenied", "denied"))).code());
        assertEquals("EXCHANGE.BAD_RESPONSE", assertThrows(ActionException.class, () -> ExchangePackage.answer(ctx(), es, "GetFolder", 200, "<html><body>Sign in</body></html>")).code());
        assertEquals("EXCHANGE.HTTP_ERROR", assertThrows(ActionException.class, () -> ExchangePackage.answer(ctx(), es, "GetFolder", 503, "Service Unavailable")).code());
    }

    @Test
    void ntlmSignIn() throws Exception {
        // the REST tests' server checks the NTLMv2 proof against the password; it answers any request with text, which is
        // not an EWS answer, so a right password reaches BAD_RESPONSE and a wrong one AUTH_FAILED
        HttpServer s = IntegratedAuthHttpTest.ntlmServer(PW);
        try {
            String u = "http://127.0.0.1:" + s.getAddress().getPort() + "/EWS/Exchange.asmx";
            assertEquals("BAD_RESPONSE", code(() -> ex.connect(ctx(), u, "TEST\\alice", new SecureString(PW, "r"), "ntlm", null, false, 30, false, "exchange")));
            assertEquals("AUTH_FAILED", code(() -> ex.connect(ctx(), u, "TEST\\alice", new SecureString("not-it", "r"), "ntlm", null, false, 30, false, "exchange")));
        } finally { s.stop(0); }
    }

    @Test
    void xmlTextIsEscaped() {
        assertEquals("a&amp;b&lt;c&gt;&quot;&apos;", ExchangePackage.x("a&b<c>\"'"));
        assertEquals("ab", ExchangePackage.x("a\u0001b"));
    }

    static String code(org.junit.jupiter.api.function.Executable run) {
        ActionException e = assertThrows(ActionException.class, run);
        return e.code().startsWith("EXCHANGE.") ? e.code().substring(9) : e.code();
    }
}
