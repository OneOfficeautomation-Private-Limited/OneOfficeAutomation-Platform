package com.oneofficeautomation.packages.email;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.runner.RunResult;
import com.oneofficeautomation.runner.Runner;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.JsonLite;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import jakarta.mail.search.SearchTerm;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Forward, reply and .eml logic of the Email package, checked on in-memory Jakarta Mail messages (no mail server needed). */
class EmailMessagesTest {
    static final Session S = Session.getInstance(new Properties());

    static MimeMessage original(String extraHeaders) throws Exception {
        String raw = "From: Ann <ann@acme.com>\r\nTo: me@corp.com, \"Bob, B.\" <bob@corp.com>\r\nCc: carol@corp.com\r\n" + extraHeaders
                + "Subject: Invoice 7\r\nDate: Mon, 19 Oct 2026 09:00:00 +0000\r\nMessage-ID: <m1@acme.com>\r\nReferences: <m0@acme.com>\r\nMIME-Version: 1.0\r\n"
                + "Content-Type: multipart/mixed; boundary=\"b1\"\r\n\r\n"
                + "--b1\r\nContent-Type: multipart/alternative; boundary=\"b2\"\r\n\r\n"
                + "--b2\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nHello team\r\nline2\r\n"
                + "--b2\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n<p>Hello team</p>\r\n--b2--\r\n"
                + "--b1\r\nContent-Type: application/pdf; name=\"INV-7.pdf\"\r\nContent-Disposition: attachment; filename=\"INV-7.pdf\"\r\nContent-Transfer-Encoding: base64\r\n\r\n"
                + Base64.getEncoder().encodeToString("%PDF-7".getBytes(StandardCharsets.UTF_8)) + "\r\n--b1--\r\n";
        return new MimeMessage(S, new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
    }

    /** Serialises and parses again, as a receiving server would. */
    static MimeMessage roundTrip(MimeMessage m) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        m.writeTo(out);
        return new MimeMessage(S, new ByteArrayInputStream(out.toByteArray()));
    }

    static String addrs(Address[] a) { return a == null ? "" : String.join(", ", Arrays.stream(a).map(x -> ((InternetAddress) x).getAddress()).toList()); }

    static Multipart parts(MimeMessage m) throws Exception { return (Multipart) m.getContent(); }

    @Test void replyKeepsThreadHeadersAndQuotesOriginal() throws Exception {
        MimeMessage r = roundTrip(EmailPackage.buildReply(original(""), "me@corp.com", "Thanks, paid.", false, false, true, List.of()));
        assertEquals("ann@acme.com", addrs(r.getRecipients(Message.RecipientType.TO)));
        assertNull(r.getRecipients(Message.RecipientType.CC));
        assertEquals("me@corp.com", addrs(r.getFrom()));
        assertEquals("Re: Invoice 7", r.getSubject());
        assertEquals("<m1@acme.com>", r.getHeader("In-Reply-To", null));
        String refs = r.getHeader("References", " ");
        assertTrue(refs.contains("<m0@acme.com>") && refs.endsWith("<m1@acme.com>"), refs);
        String body = (String) r.getContent();
        assertTrue(body.startsWith("Thanks, paid.\r\n\r\nOn "), body);
        assertTrue(body.contains("Ann <ann@acme.com> wrote:\r\n> Hello team\r\n> line2"), body);
    }

    @Test void replyAllLeavesOutOwnAddressAndUsesReplyTo() throws Exception {
        MimeMessage r = roundTrip(EmailPackage.buildReply(original("Reply-To: billing@acme.com\r\n"), "ME@corp.com", "<b>Done</b>", true, true, true, List.of()));
        assertEquals("billing@acme.com, bob@corp.com", addrs(r.getRecipients(Message.RecipientType.TO)));
        assertEquals("carol@corp.com", addrs(r.getRecipients(Message.RecipientType.CC)));
        String html = (String) r.getContent();
        assertTrue(r.isMimeType("text/html"));
        assertTrue(html.startsWith("<b>Done</b>") && html.contains("<blockquote") && html.contains("<p>Hello team</p>"), html);
    }

    @Test void replyWithAttachmentAndWithoutQuote() throws Exception {
        Path f = Files.createTempFile("ooa-mail", ".txt");
        Files.writeString(f, "report");
        MimeBodyPart part = new MimeBodyPart();
        part.attachFile(f.toFile());
        MimeMessage r = roundTrip(EmailPackage.buildReply(original(""), "me@corp.com", "See attached.", false, false, false, List.of(part)));
        Multipart mp = parts(r);
        assertEquals(2, mp.getCount());
        assertEquals("See attached.", mp.getBodyPart(0).getContent());
        assertEquals(f.getFileName().toString(), mp.getBodyPart(1).getFileName());
    }

    @Test void forwardInlineCarriesNoteHeaderBlockAndAttachments() throws Exception {
        MimeMessage fw = roundTrip(EmailPackage.buildForward(S, original(""), "me@corp.com", "audit@corp.com; team@corp.com", "lead@corp.com", "Please file.", false, false));
        assertEquals("Fwd: Invoice 7", fw.getSubject());
        assertEquals("audit@corp.com, team@corp.com", addrs(fw.getRecipients(Message.RecipientType.TO)));
        assertEquals("lead@corp.com", addrs(fw.getRecipients(Message.RecipientType.CC)));
        assertNull(fw.getHeader("In-Reply-To"));
        Multipart mp = parts(fw);
        assertEquals(2, mp.getCount());
        String text = (String) mp.getBodyPart(0).getContent();
        assertTrue(text.startsWith("Please file.") && text.contains("---------- Forwarded message ---------") && text.contains("From: Ann <ann@acme.com>")
                && text.contains("Subject: Invoice 7") && text.contains("Hello team"), text);
        BodyPart att = mp.getBodyPart(1);
        assertEquals("INV-7.pdf", att.getFileName());
        assertEquals("%PDF-7", new String(att.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test void forwardAsAttachmentEmbedsOriginalEml() throws Exception {
        MimeMessage fw = roundTrip(EmailPackage.buildForward(S, original(""), "me@corp.com", List.of("audit@corp.com"), null, "FYI", false, true));
        Multipart mp = parts(fw);
        assertEquals(2, mp.getCount());
        BodyPart att = mp.getBodyPart(1);
        assertTrue(att.isMimeType("message/rfc822"));
        assertEquals("Invoice 7.eml", att.getFileName());
        MimeMessage inner = new MimeMessage(S, att.getInputStream());
        assertEquals("Invoice 7", inner.getSubject());
        assertEquals("<m1@acme.com>", inner.getMessageID());
        // an already forwarded subject is not prefixed twice
        MimeMessage again = EmailPackage.buildForward(S, fw, "me@corp.com", "x@y.com", null, "", false, true);
        assertEquals("Fwd: Invoice 7", again.getSubject());
    }

    @Test void forwardRejectsMissingOrInvalidRecipients() throws Exception {
        MimeMessage o = original("");
        ActionException none = assertThrows(ActionException.class, () -> EmailPackage.buildForward(S, o, "me@corp.com", "", null, "", false, false));
        assertEquals("EMAIL.NO_RECIPIENT", none.code());
        ActionException bad = assertThrows(ActionException.class, () -> EmailPackage.buildForward(S, o, "me@corp.com", "not an <address", null, "", false, false));
        assertEquals("EMAIL.BAD_ADDRESS", bad.code());
        ActionException badFrom = assertThrows(ActionException.class, () -> EmailPackage.buildReply(o, "bad <", "x", false, false, false, List.of()));
        assertEquals("EMAIL.BAD_ADDRESS", badFrom.code());
    }

    @Test void emlIsWrittenCompleteAndReadable() throws Exception {
        Path p = Files.createTempDirectory("ooa-eml").resolve("sub/invoice.eml");
        EmailPackage.writeEml(original(""), p);
        MimeMessage back = new MimeMessage(S, Files.newInputStream(p));
        assertEquals("Invoice 7", back.getSubject());
        assertEquals("INV-7.pdf", parts(back).getBodyPart(1).getFileName());
        assertTrue(Files.readString(p).contains("Message-ID: <m1@acme.com>"));
    }

    @Test void filterForBulkActionsMatchesSenderSubjectAndUnread() throws Exception {
        assertNull(EmailPackage.filter(false, null, " ", null, null), "no filter means all emails");
        MimeMessage o = original("");
        o.setFlag(Flags.Flag.SEEN, false);
        SearchTerm t = EmailPackage.filter(true, "acme", "invoice", null, null);
        assertTrue(t.match(o));
        assertFalse(EmailPackage.filter(false, "globex", null, null, null).match(o));
        o.setFlag(Flags.Flag.SEEN, true);
        assertFalse(t.match(o), "read emails excluded by unread_only");
        assertNotNull(EmailPackage.filter(false, null, null, LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2027, 1, 1, 0, 0)));
    }

    @Test void newMailboxActionsNeedAnOpenSession() {
        for (String step : List.of(
                "{\"id\":\"s\",\"action\":\"ooa.email/folder_exists\",\"args\":{\"folder\":\"Processed\"}}",
                "{\"id\":\"s\",\"action\":\"ooa.email/move_all\",\"args\":{\"to_folder\":\"Processed\"}}",
                "{\"id\":\"s\",\"action\":\"ooa.email/delete_all\",\"args\":{\"subject_contains\":\"x\"}}",
                "{\"id\":\"s\",\"action\":\"ooa.email/save_email_as_eml\",\"args\":{\"id\":1,\"local_path\":\"a.eml\"}}")) {
            @SuppressWarnings("unchecked") Map<String, Object> flow = (Map<String, Object>) JsonLite.parse(
                    "{\"kind\":\"TaskBot\",\"id\":\"t\",\"name\":\"t\",\"variables\":[],\"steps\":[" + step + "]}");
            RunResult r = Runner.builder(FirstPartyPackages.registry()).workDir(Path.of(System.getProperty("java.io.tmpdir"))).build().run(flow, Map.of());
            assertFalse(r.ok(), step);
            assertEquals("SESSION.NOT_FOUND", r.error().get("code"), step);
        }
    }
}
