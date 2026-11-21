package com.oneofficeautomation.packages.email;

import com.icegreen.greenmail.store.StoredMessage;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.SecureString;
import jakarta.mail.Flags;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C1 (21 Nov 2026): reading an email's body or saving its attachments must not mark an unread email read unless the
 * step asks for it. Checked against an in-memory IMAP server, whose own store says whether the email is read.
 */
class EmailReadStatusTest {
    static final String USER = "ap@corp.test", PASSWORD = "pw-ReadStatus-1";

    GreenMail mail;
    GreenMailUser user;
    Path work;
    ActionContext ctx;
    final EmailPackage pkg = new EmailPackage();

    @BeforeEach void start() throws Exception {
        mail = new GreenMail(new ServerSetup[]{new ServerSetup(0, "127.0.0.1", "smtp"), new ServerSetup(0, "127.0.0.1", "imap")});
        mail.start();
        user = mail.setUser(USER, USER, PASSWORD);
        for (int i = 1; i <= 2; i++) user.deliver(withAttachment("Invoice " + i));
        work = Files.createTempDirectory("ooa-mail-read");
        ctx = new ActionContext("run1", "step1", "t", work, new HashMap<>(), new HashMap<>(), new AtomicBoolean(), List::of, (l, m) -> { });
        pkg.connect(ctx, "127.0.0.1", mail.getImap().getPort(), USER, new SecureString(PASSWORD, "test/mail/password"), null, "imap", "none", null, 15, "mail", null, 587, "starttls");
    }

    @AfterEach void stop() {
        pkg.disconnect(ctx, "mail");
        mail.stop();
    }

    static MimeMessage withAttachment(String subject) throws Exception {
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties()));
        m.setFrom(new InternetAddress("ann@acme.test"));
        m.setRecipients(Message.RecipientType.TO, USER);
        m.setSubject(subject);
        MimeMultipart mp = new MimeMultipart();
        MimeBodyPart text = new MimeBodyPart();
        text.setText("Please pay " + subject, "UTF-8");
        MimeBodyPart att = new MimeBodyPart();
        att.setContent("%PDF-1.4 fake", "application/pdf");
        att.setFileName(subject.replace(' ', '_') + ".pdf");
        att.setDisposition(MimeBodyPart.ATTACHMENT);
        mp.addBodyPart(text);
        mp.addBodyPart(att);
        m.setContent(mp);
        m.saveChanges();
        return m;
    }

    /** Read status as the server keeps it, newest last. */
    List<Boolean> seen() throws Exception {
        List<Boolean> out = new ArrayList<>();
        for (StoredMessage s : mail.getManagers().getImapHostManager().getInbox(user).getMessages()) out.add(s.isSet(Flags.Flag.SEEN));
        return out;
    }

    long firstId() { return ((Number) ((List<?>) ((List<?>) ((Map<?, ?>) pkg.list(ctx, "INBOX", false, null, null, null, 10, null, false, null, null, false, true, "mail").get("table")).get("rows")).get(0)).get(0)).longValue(); }

    @Test void readEmailWithoutMarkReadKeepsItUnread() throws Exception {
        Map<?, ?> e = (Map<?, ?>) pkg.get(ctx, firstId(), "INBOX", false, "mail").get("email");
        assertTrue(String.valueOf(e.get("text")).contains("Please pay Invoice 1"), "the body was read");
        assertEquals(true, e.get("unread"));
        assertEquals(List.of(false, false), seen(), "reading the body must not mark the email read");
    }

    @Test void readEmailWithMarkReadMarksOnlyThatEmail() throws Exception {
        pkg.get(ctx, firstId(), "INBOX", true, "mail");
        assertEquals(List.of(true, false), seen());
    }

    @Test void eachEmailWithBodyKeepsEmailsUnread() throws Exception {
        int n = 0;
        for (Object o : pkg.emails(ctx, "INBOX", false, null, null, null, 10, null, false, null, null, false, false, "mail", true)) {
            assertTrue(String.valueOf(((Map<?, ?>) o).get("text")).contains("Please pay"));
            n++;
        }
        assertEquals(2, n);
        assertEquals(List.of(false, false), seen(), "the loop's 'with the text and HTML' tick promises the email stays unread");
    }

    @Test void saveAttachmentsKeepsEmailUnread() throws Exception {
        List<?> files = (List<?>) pkg.saveAttachments(ctx, firstId(), work.resolve("out").toString(), "*", "INBOX", false, true, "mail").get("files");
        assertEquals(1, files.size());
        assertEquals(List.of(false, false), seen());
    }

    @Test void saveAllAttachmentsMarksReadOnlyWhenAsked() throws Exception {
        List<?> files = (List<?>) pkg.saveAllAttachments(ctx, work.resolve("all").toString(), "*", "INBOX", false, null, null, null, null, false, false, "mail").get("files");
        assertEquals(2, files.size());
        assertEquals(List.of(false, false), seen(), "mark_read false keeps the emails unread");
        pkg.saveAllAttachments(ctx, work.resolve("all2").toString(), "*", "INBOX", false, null, null, null, null, false, true, "mail");
        assertEquals(List.of(true, true), seen());
    }
}
