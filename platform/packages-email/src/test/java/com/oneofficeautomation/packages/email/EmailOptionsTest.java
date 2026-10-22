package com.oneofficeautomation.packages.email;

import com.oneofficeautomation.sdk.ActionException;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import jakarta.mail.search.SearchTerm;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

import static com.oneofficeautomation.packages.email.EmailMessagesTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Options added to the Email package after the comparison with other RPA mail packages, checked on in-memory messages. */
class EmailOptionsTest {

    static MimeBodyPart file(String name, String content) throws Exception {
        Path f = Files.createTempDirectory("ooa-mail").resolve(name);
        Files.writeString(f, content);
        MimeBodyPart p = new MimeBodyPart();
        p.attachFile(f.toFile());
        return p;
    }

    @Test void connectionChoicesMapToProtocolPortAndLogin() {
        assertEquals("imaps", EmailPackage.storeProtocol(null, null), "default stays IMAP over SSL");
        assertEquals("imap", EmailPackage.storeProtocol("imap", "starttls"));
        assertEquals("pop3s", EmailPackage.storeProtocol("POP3", "ssl"));
        assertEquals("pop3", EmailPackage.storeProtocol("pop3", "none"));
        assertEquals("EMAIL.BAD_INPUT", assertThrows(ActionException.class, () -> EmailPackage.storeProtocol("ews", "ssl")).code());
        assertEquals(993, EmailPackage.readPort("imaps", "ssl", 993));
        assertEquals(143, EmailPackage.readPort("imap", "starttls", 993));
        assertEquals(995, EmailPackage.readPort("pop3s", "ssl", 993));
        assertEquals(110, EmailPackage.readPort("pop3", "none", 993));
        assertEquals(1143, EmailPackage.readPort("imap", "none", 1143), "an explicit port is kept");
        assertEquals("me@corp.com", EmailPackage.loginName("me@corp.com", " ", false));
        assertEquals("me@corp.com\\ap@corp.com", EmailPackage.loginName("me@corp.com", "ap@corp.com", false), "password sign-in to a shared mailbox");
        assertEquals("ap@corp.com", EmailPackage.loginName("me@corp.com", "ap@corp.com", true), "OAuth sign-in names the shared mailbox");
        Properties p = EmailPackage.props("pop3s", "pop.corp.com", 995, "ssl", false, 30);
        assertEquals("30000", p.get("mail.pop3s.connectiontimeout"));
        assertEquals("true", p.get("mail.pop3s.ssl.enable"));
    }

    @Test void sendBuildsImportanceReplyToReceiptCopiesAndInlineImages() throws Exception {
        MimeBodyPart logo = file("logo.png", "PNG");
        logo.setContentID("<logo.png>");
        logo.setDisposition(Part.INLINE);
        MimeMessage m = roundTrip(EmailPackage.buildMessage(S, "ap@corp.com", "a@x.com; b@x.com", "c@x.com", List.of("d@x.com"), "Invoice", "<img src=\"cid:logo.png\">", true,
                List.of(file("inv.pdf", "%PDF")), List.of(logo), "high", "billing@corp.com", true));
        assertEquals("High", m.getHeader("Importance", null));
        assertEquals("1 (Highest)", m.getHeader("X-Priority", null));
        assertEquals("billing@corp.com", addrs(m.getReplyTo()));
        assertEquals("ap@corp.com", m.getHeader("Disposition-Notification-To", null));
        assertEquals("a@x.com, b@x.com", addrs(m.getRecipients(Message.RecipientType.TO)));
        assertEquals("c@x.com", addrs(m.getRecipients(Message.RecipientType.CC)));
        Multipart mixed = parts(m);
        assertEquals(2, mixed.getCount());
        Multipart related = (Multipart) mixed.getBodyPart(0).getContent();
        assertTrue(related.getContentType().startsWith("multipart/related"), related.getContentType());
        assertTrue(related.getBodyPart(0).isMimeType("text/html"));
        assertEquals("<logo.png>", ((MimeBodyPart) related.getBodyPart(1)).getContentID());
        assertEquals("inv.pdf", mixed.getBodyPart(1).getFileName());
        assertEquals("high", EmailPackage.importanceOf(m));

        MimeMessage plain = EmailPackage.buildMessage(S, "ap@corp.com", "a@x.com", null, null, "Hi", "x", false, List.of(), List.of(), "normal", null, false);
        assertNull(plain.getHeader("Importance"), "normal adds no header, as before");
        assertNull(plain.getHeader("Disposition-Notification-To"));
        assertEquals("normal", EmailPackage.importanceOf(plain));
        assertEquals("EMAIL.BAD_INPUT", assertThrows(ActionException.class, () ->
                EmailPackage.buildMessage(S, "ap@corp.com", "a@x.com", null, null, "Hi", "x", false, List.of(), List.of(logo), null, null, false)).code(), "inline images need HTML");
        assertEquals("EMAIL.BAD_INPUT", assertThrows(ActionException.class, () ->
                EmailPackage.buildMessage(S, "ap@corp.com", "a@x.com", null, null, "Hi", "x", false, List.of(), List.of(), "urgent", null, false)).code());
        MimeMessage low = EmailPackage.buildMessage(S, "ap@corp.com", "a@x.com", null, null, "Hi", "x", false, List.of(), List.of(), "LOW", null, false);
        assertEquals("low", EmailPackage.importanceOf(low));
    }

    @Test void forwardAndReplyTakeBccImportanceReplyToFilesAndExcludeSender() throws Exception {
        MimeMessage fw = roundTrip(EmailPackage.buildForward(S, original(""), "me@corp.com", "audit@corp.com", null, "boss@corp.com", "FYI", false, false,
                List.of(file("note.txt", "n")), "low", "desk@corp.com"));
        assertEquals("boss@corp.com", addrs(fw.getRecipients(Message.RecipientType.BCC)));
        assertEquals("Low", fw.getHeader("Importance", null));
        assertEquals("desk@corp.com", addrs(fw.getReplyTo()));
        Multipart mp = parts(fw);
        assertEquals(3, mp.getCount(), "body, original attachment, extra file");
        assertEquals("note.txt", mp.getBodyPart(2).getFileName());

        MimeMessage r = roundTrip(EmailPackage.buildReply(original(""), "me@corp.com", "ok", false, true, false, List.of(), "lead@corp.com", "arch@corp.com", "high", null, true));
        assertEquals("bob@corp.com", addrs(r.getRecipients(Message.RecipientType.TO)), "sender and own address left out");
        assertEquals("carol@corp.com, lead@corp.com", addrs(r.getRecipients(Message.RecipientType.CC)));
        assertEquals("arch@corp.com", addrs(r.getRecipients(Message.RecipientType.BCC)));
        assertEquals("High", r.getHeader("Importance", null));
        MimeMessage keep = roundTrip(EmailPackage.buildReply(original(""), "me@corp.com", "ok", false, true, false, List.of()));
        assertEquals("ann@acme.com, bob@corp.com", addrs(keep.getRecipients(Message.RecipientType.TO)), "default keeps the sender");
    }

    @Test void filtersReadStatusSeveralValuesRecipientBodyAndSentDate() throws Exception {
        MimeMessage o = original("");
        o.setFlag(Flags.Flag.SEEN, true);
        assertTrue(EmailPackage.filter(false, true, null, null, null, null, null, null, false).match(o), "read_only matches a read email");
        o.setFlag(Flags.Flag.SEEN, false);
        assertFalse(EmailPackage.filter(false, true, null, null, null, null, null, null, false).match(o));
        assertEquals("EMAIL.BAD_INPUT", assertThrows(ActionException.class, () -> EmailPackage.filter(true, true, null, null, null, null, null, null, false)).code());
        assertTrue(EmailPackage.filter(false, false, "globex; acme", "receipt;invoice", null, null, null, null, false).match(o), "any of the ; values");
        assertFalse(EmailPackage.filter(false, false, null, "receipt;order", null, null, null, null, false).match(o));
        assertTrue(EmailPackage.filter(false, false, null, null, null, null, "bob@corp", "hello team", false).match(o));
        assertFalse(EmailPackage.filter(false, false, null, null, null, null, "carol@", null, false).match(o), "to_contains looks at To only");
        SearchTerm sent = EmailPackage.filter(false, false, null, null, LocalDateTime.of(2026, 10, 1, 0, 0), LocalDateTime.of(2026, 11, 1, 0, 0), null, null, true);
        assertTrue(sent.match(o), "POP3 compares the sent date");
        assertFalse(EmailPackage.filter(false, false, null, null, LocalDateTime.of(2026, 10, 20, 0, 0), null, null, null, true).match(o));
    }

    @Test void detailsHaveReplyToImportanceMessageIdAndFlag() throws Exception {
        MimeMessage o = original("Reply-To: billing@acme.com\r\nX-Priority: 2\r\n");
        o.setFlag(Flags.Flag.FLAGGED, true);
        Map<String, Object> e = EmailPackage.details(o, 7);
        assertEquals(BigDecimal.valueOf(7), e.get("id"));
        assertEquals(List.of("billing@acme.com"), e.get("reply_to"));
        assertEquals("high", e.get("importance"));
        assertEquals("<m1@acme.com>", e.get("message_id"));
        assertEquals(true, e.get("flagged"));
        assertEquals(LocalDateTime.of(2026, 10, 19, 9, 0), e.get("sent"));
        assertEquals(List.of("INV-7.pdf"), e.get("attachments"));
    }

    @Test void attachmentsOverwriteOrNumberAndSkipInline() throws Exception {
        Path dir = Files.createTempDirectory("ooa-att");
        assertEquals(List.of(dir.resolve("INV-7.pdf").toString()), EmailPackage.saveParts(original(""), dir, "*.pdf", false, true));
        assertEquals(List.of(dir.resolve("INV-7 (2).pdf").toString()), EmailPackage.saveParts(original(""), dir, "*", false, true), "numbered when overwrite is off");
        assertEquals(List.of(dir.resolve("INV-7.pdf").toString()), EmailPackage.saveParts(original(""), dir, null, true, true), "replaced when overwrite is on");
        assertEquals("%PDF-7", Files.readString(dir.resolve("INV-7.pdf")));
        try (var s = Files.list(dir)) { assertEquals(2, s.count()); }

        MimeBodyPart logo = file("logo.png", "PNG");
        logo.setDisposition(Part.INLINE);
        MimeMessage withLogo = roundTrip(EmailPackage.buildMessage(S, "a@x.com", "b@x.com", null, null, "s", "<img src=\"cid:logo.png\">", true,
                List.of(file("a.txt", "a")), List.of(logo), null, null, false));
        assertEquals(2, EmailPackage.saveParts(withLogo, Files.createTempDirectory("ooa-att"), "*", false, true).size());
        Path d2 = Files.createTempDirectory("ooa-att");
        assertEquals(List.of(d2.resolve("a.txt").toString()), EmailPackage.saveParts(withLogo, d2, "*", false, false), "inline images skipped");
    }

    @Test void emlTargetNamesAfterSubjectAndAvoidsOverwrite() throws Exception {
        Path dir = Files.createTempDirectory("ooa-eml");
        assertEquals(dir.resolve("Invoice 7.eml"), EmailPackage.emlTarget(dir, false, "Invoice 7", true), "an existing folder gets a subject file name");
        assertEquals(dir.resolve("new").resolve("Re_ x_y.eml"), EmailPackage.emlTarget(dir.resolve("new"), true, "Re: x/y", true), "no path comes from the subject");
        Path f = dir.resolve("a.eml");
        Files.writeString(f, "x");
        assertEquals(f, EmailPackage.emlTarget(f, false, "s", true));
        assertEquals(dir.resolve("a (2).eml"), EmailPackage.emlTarget(f, false, "s", false));
    }
}
