package com.oneofficeautomation.packages.office;

import com.oneofficeautomation.packages.Microsoft365Package;
import com.oneofficeautomation.packages.MsgFile;
import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.SecureString;
import com.sun.net.httpserver.HttpServer;
import org.apache.poi.hsmf.MAPIMessage;
import org.apache.poi.hsmf.datatypes.AttachmentChunks;
import org.apache.poi.hsmf.datatypes.RecipientChunks;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Gap: save emails as Outlook .msg files; read back with Apache POI's .msg reader (HSMF). */
class MsgWriterTest {

    @Test void writesAMessageOutlookCanOpen() throws Exception {
        Path dir = Files.createTempDirectory("ooa-msg");
        MsgFile.Message m = new MsgFile.Message();
        m.subject = "Invoice 4711 – überfällig";
        m.bodyHtml = "<html><body><p>Hello <b>Ann</b>,</p><p>see the logo <img src=\"cid:logo1\"></p></body></html>";
        m.from = new MsgFile.Address("Accounts Payable", "ap@acme.example");
        m.to.add(new MsgFile.Address("Ann Lee", "ann@contoso.example"));
        m.cc.add(new MsgFile.Address("", "bob@contoso.example"));
        m.bcc.add(new MsgFile.Address("Audit", "audit@acme.example"));
        m.sent = Instant.parse("2026-11-02T09:30:00Z");
        m.received = Instant.parse("2026-11-02T09:30:05Z");
        m.messageId = "<abc@acme.example>";
        m.importance = "high";
        m.attachments.add(new MsgFile.Attachment("invoice.pdf", "application/pdf", "%PDF-1.4 test".getBytes(StandardCharsets.US_ASCII), null, false));
        m.attachments.add(new MsgFile.Attachment("logo.png", "image/png", new byte[]{(byte) 0x89, 'P', 'N', 'G'}, "logo1", true));
        Path p = dir.resolve("mail/out.msg");
        MsgFile.write(m, p);  // found through ServiceLoader

        try (MAPIMessage msg = new MAPIMessage(p.toFile())) {
            assertEquals("Invoice 4711 – überfällig", msg.getSubject());
            assertEquals("Accounts Payable", msg.getDisplayFrom());
            assertEquals("Ann Lee", msg.getDisplayTo());
            assertEquals("bob@contoso.example", msg.getDisplayCC());
            assertEquals("Audit", msg.getDisplayBCC());
            assertTrue(msg.getHtmlBody().contains("Hello <b>Ann</b>"), msg.getHtmlBody());
            assertTrue(msg.getTextBody().contains("Hello Ann,"), msg.getTextBody());
            assertEquals("IPM.Note", msg.getStringFromChunk(msg.getMainChunks().getMessageClass()));
            assertEquals(List.of("ann@contoso.example", "bob@contoso.example", "audit@acme.example"), Arrays.asList(msg.getRecipientEmailAddressList()));
            RecipientChunks[] rc = msg.getRecipientDetailsChunks();
            assertEquals(3, rc.length);
            AttachmentChunks[] at = msg.getAttachmentFiles();
            assertEquals(2, at.length);
            assertEquals("invoice.pdf", at[0].getAttachLongFileName().getValue());
            assertArrayEquals("%PDF-1.4 test".getBytes(StandardCharsets.US_ASCII), at[0].getAttachData().getValue());
            assertEquals("application/pdf", at[0].getAttachMimeTag().getValue());
            assertEquals("logo1", at[1].getAttachContentId().getValue());
            Calendar sent = msg.getMessageDate();
            assertEquals(Instant.parse("2026-11-02T09:30:00Z"), sent.toInstant());
        }
        assertEquals(List.of("out.msg"), Files.list(dir.resolve("mail")).map(x -> x.getFileName().toString()).toList(), "no temporary file left behind");
        // a text-only message
        MsgFile.Message t = new MsgFile.Message();
        t.subject = "Plain";
        t.bodyText = "line 1\nline 2";
        MsgFile.write(t, dir.resolve("plain.msg"));
        try (MAPIMessage msg = new MAPIMessage(dir.resolve("plain.msg").toFile())) {
            assertEquals("line 1\nline 2", msg.getTextBody());
            assertEquals(0, msg.getAttachmentFiles().length);
        }
    }

    @Test void outlookMailSavedAsMsgThroughGraph() throws Exception {
        HttpServer graph = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Map<String, String> routes = new HashMap<>();
        routes.put("/v1.0/me/messages/M1", "{\"subject\":\"Order 99\",\"body\":{\"contentType\":\"html\",\"content\":\"<p>Thanks</p>\"},"
                + "\"from\":{\"emailAddress\":{\"name\":\"Shop\",\"address\":\"shop@example.com\"}},\"toRecipients\":[{\"emailAddress\":{\"name\":\"Me\",\"address\":\"me@example.com\"}}],"
                + "\"ccRecipients\":[],\"bccRecipients\":[],\"sentDateTime\":\"2026-11-01T08:00:00Z\",\"receivedDateTime\":\"2026-11-01T08:00:02Z\",\"internetMessageId\":\"<o99@example.com>\","
                + "\"importance\":\"normal\",\"isRead\":true,\"hasAttachments\":true}");
        routes.put("/v1.0/me/messages/M1/attachments", "{\"value\":[{\"@odata.type\":\"#microsoft.graph.fileAttachment\",\"id\":\"A1\",\"name\":\"order.csv\",\"contentType\":\"text/csv\","
                + "\"contentBytes\":\"" + Base64.getEncoder().encodeToString("sku,qty\nA,1\n".getBytes()) + "\",\"isInline\":false},"
                + "{\"@odata.type\":\"#microsoft.graph.itemAttachment\",\"id\":\"A2\",\"name\":\"Forwarded\"},"
                + "{\"@odata.type\":\"#microsoft.graph.referenceAttachment\",\"id\":\"A3\",\"name\":\"Link\"}]}");
        routes.put("/v1.0/me/messages/M1/attachments/A2/$value", "From: x@example.com\r\nSubject: inner\r\n\r\nhi");
        graph.createContext("/", ex -> {
            String body = routes.get(ex.getRequestURI().getRawPath().replace("%24", "$"));
            byte[] b = (body == null ? "{\"error\":{\"message\":\"no route\"}}" : body).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", body != null && body.startsWith("From:") ? "message/rfc822" : "application/json");
            ex.sendResponseHeaders(body == null ? 404 : 200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        graph.start();
        try {
            Path dir = Files.createTempDirectory("ooa-msg-graph");
            ActionContext ctx = OfficeTestSupport.ctx(dir);
            Files.createDirectories(dir.resolve("mail"));
            Map<String, Object> r = new Microsoft365Package().saveMsg(ctx, new SecureString("tok", "t"), "M1", "mail/", true, "me",
                    "http://127.0.0.1:" + graph.getAddress().getPort() + "/v1.0");
            assertTrue(r.get("path").toString().endsWith("Order 99.msg"), r.toString());
            assertEquals(2, ((Number) r.get("attachments")).intValue(), "the link attachment is skipped");
            try (MAPIMessage msg = new MAPIMessage(Path.of(r.get("path").toString()).toFile())) {
                assertEquals("Order 99", msg.getSubject());
                assertEquals("<p>Thanks</p>", msg.getHtmlBody());
                assertEquals("Shop", msg.getDisplayFrom());
                assertEquals("order.csv", msg.getAttachmentFiles()[0].getAttachLongFileName().getValue());
                assertEquals("Forwarded.eml", msg.getAttachmentFiles()[1].getAttachLongFileName().getValue());
            }
            Map<String, Object> again = new Microsoft365Package().saveMsg(ctx, new SecureString("tok", "t"), "M1", "mail/", false, "me",
                    "http://127.0.0.1:" + graph.getAddress().getPort() + "/v1.0");
            assertTrue(again.get("path").toString().endsWith("Order 99 (2).msg"), again.toString());
        } finally { graph.stop(0); }
    }
}
