package com.oneofficeautomation.packages.email;

import com.icegreen.greenmail.store.StoredMessage;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.SecureString;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Email options added for AA parity (G5, 21 Nov 2026), against an in-memory SMTP/IMAP server: several folders and *
 * patterns, one read-status choice, a "quoted" exact subject, mark read in the loop, more loop fields, local time, steps
 * that act on the loop's current email, Save email named from the subject, and the send options (body type, footer,
 * missing attachments, invalid addresses, character set, reply subject).
 */
class EmailParityOptionsTest {
    static final String USER = "ap@corp.test", PASSWORD = "pw-Parity-2";

    GreenMail mail;
    GreenMailUser user;
    Path work;
    ActionContext ctx;
    final EmailPackage pkg = new EmailPackage();
    final List<String> warnings = new ArrayList<>();

    @BeforeEach void start() throws Exception {
        mail = new GreenMail(new ServerSetup[]{new ServerSetup(0, "127.0.0.1", "smtp"), new ServerSetup(0, "127.0.0.1", "imap")});
        mail.start();
        user = mail.setUser(USER, USER, PASSWORD);
        user.deliver(message("Invoice 1", "ann@acme.test", "cc1@corp.test", true));
        user.deliver(message("Invoice 10", "bob@acme.test", null, false));
        user.deliver(message("Hello", "ann@acme.test", null, false));
        work = Files.createTempDirectory("ooa-mail-parity");
        ctx = context(null);
        pkg.connect(ctx, "127.0.0.1", mail.getImap().getPort(), USER, secret(), null, "imap", "none", null, 15, "mail",
                "127.0.0.1", mail.getSmtp().getPort(), "none");
    }

    @AfterEach void stop() {
        pkg.disconnect(ctx, "mail");
        mail.stop();
    }

    static SecureString secret() { return new SecureString(PASSWORD, "test/mail/password"); }

    ActionContext context(Object loopItem) {
        Map<String, Object> services = new HashMap<>();
        if (loopItem != null) services.put(ActionContext.LOOP_ITEM_SERVICE, (Supplier<Object>) () -> loopItem);
        Map<String, Object> sessions = ctx == null ? new HashMap<>() : ctx.sessions();
        return new ActionContext("run1", "step1", "t", work, sessions, services, new AtomicBoolean(), List::of, (l, m) -> { if (l.startsWith("warn")) warnings.add(m); });
    }

    static MimeMessage message(String subject, String from, String cc, boolean attachment) throws Exception {
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties()));
        m.setFrom(new InternetAddress(from));
        m.setRecipients(Message.RecipientType.TO, USER);
        if (cc != null) m.setRecipients(Message.RecipientType.CC, cc);
        m.setSubject(subject);
        m.setSentDate(new Date(1_790_000_000_000L));
        if (attachment) {
            MimeMultipart mp = new MimeMultipart();
            MimeBodyPart text = new MimeBodyPart();
            text.setText("Body of " + subject, "UTF-8");
            MimeBodyPart a = new MimeBodyPart(), b = new MimeBodyPart();
            a.setContent("one", "text/csv"); a.setFileName("a.csv"); a.setDisposition(Part.ATTACHMENT);
            b.setContent("%PDF", "application/pdf"); b.setFileName("b.pdf"); b.setDisposition(Part.ATTACHMENT);
            mp.addBodyPart(text); mp.addBodyPart(a); mp.addBodyPart(b);
            m.setContent(mp);
        } else m.setText("Body of " + subject, "UTF-8");
        m.saveChanges();
        return m;
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows(Map<String, Object> table) {
        Map<String, Object> t = (Map<String, Object>) table.get("table");
        List<String> cols = (List<String>) t.get("columns");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object r : (List<Object>) t.get("rows")) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < cols.size(); i++) m.put(cols.get(i), ((List<Object>) r).get(i));
            out.add(m);
        }
        return out;
    }

    List<Map<String, Object>> find(String folder, String subject, String status) {
        return rows(pkg.list(ctx, folder, false, null, subject, null, 0, null, false, null, null, false, true, "mail", status, false));
    }

    void addFolder(String name, String... subjects) throws Exception {
        Store store = ((EmailPackage.MailSession) ctx.sessions().get("mail")).store;
        Folder f = store.getFolder(name);
        if (!f.exists()) f.create(Folder.HOLDS_MESSAGES);
        List<Message> ms = new ArrayList<>();
        for (String s : subjects) ms.add(message(s, "zed@acme.test", null, false));
        f.appendMessages(ms.toArray(new Message[0]));
    }

    List<Boolean> inboxSeen() throws Exception {
        List<Boolean> out = new ArrayList<>();
        for (StoredMessage s : mail.getManagers().getImapHostManager().getInbox(user).getMessages()) out.add(s.isSet(Flags.Flag.SEEN));
        return out;
    }

    @Test void severalFoldersAndPatterns() throws Exception {
        addFolder("Inv2025", "Old invoice");
        addFolder("Inv2026", "New invoice", "Newer invoice");
        List<Map<String, Object>> all = find("INBOX; Inv*", null, "any");
        assertEquals(6, all.size());
        assertEquals(Set.of("INBOX", "Inv2025", "Inv2026"), new HashSet<>(all.stream().map(r -> r.get("folder")).toList()));
        assertEquals(3, find("Inv*", null, "any").size());
        assertEquals("EMAIL.NO_FOLDER", assertThrows(ActionException.class, () -> find("Nothing*", null, "any")).code());
        Map<String, Object> moved = pkg.moveAll(ctx, "Done", "Inv*", false, null, null, null, null, false, null, null, true, "mail", "any");
        assertEquals(0, BigDecimal.valueOf(3).compareTo((BigDecimal) moved.get("count")));
        assertEquals(3, find("Done", null, "any").size());
        Map<String, Object> deleted = pkg.deleteAll(ctx, "Done; INBOX", false, null, "invoice", null, null, true, true, false, null, null, "mail", "any");
        assertEquals(0, BigDecimal.valueOf(5).compareTo((BigDecimal) deleted.get("count")), "3 in Done and Invoice 1 / Invoice 10 in INBOX");
    }

    @Test void statusChoiceAndOlderTicks() throws Exception {
        long first = ((Number) find("INBOX", null, "any").get(0).get("id")).longValue();
        pkg.mark(ctx, first, true, "INBOX", null, "mail");
        assertEquals(1, find("INBOX", null, "read").size());
        assertEquals(2, find("INBOX", null, "unread").size());
        assertEquals(2, rows(pkg.list(ctx, "INBOX", true, null, null, null, 0, null, false, null, null, false, true, "mail", "any", false)).size(), "the old unread_only tick still works");
        assertEquals("EMAIL.BAD_INPUT", assertThrows(ActionException.class, () -> find("INBOX", null, "new")).code());
    }

    @Test void quotedSubjectIsExact() {
        assertEquals(2, find("INBOX", "Invoice 1", "any").size(), "contains: Invoice 1 and Invoice 10");
        List<Map<String, Object>> exact = find("INBOX", "\"Invoice 1\"", "any");
        assertEquals(1, exact.size());
        assertEquals("Invoice 1", exact.get(0).get("subject"));
        assertEquals(2, find("INBOX", "\"invoice 10\"; Hello", "any").size(), "exact and contains mixed, case ignored");
        assertTrue(EmailPackage.subjectMatches("RE: Invoice 1", "Invoice 1"));
        assertFalse(EmailPackage.subjectMatches("RE: Invoice 1", "\"Invoice 1\""));
    }

    @Test void loopFieldsLocalTimeAndMarkRead() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object o : pkg.emails(ctx, "INBOX", false, null, null, null, 0, null, false, null, null, false, true, "mail", false, "any", true, true))
            rows.add(new LinkedHashMap<>((Map<String, Object>) o));
        assertEquals(3, rows.size());
        Map<String, Object> first = rows.get(0);
        assertEquals(List.of("cc1@corp.test"), first.get("cc"));
        assertEquals(0, BigDecimal.valueOf(2).compareTo((BigDecimal) first.get("attachment_count")));
        assertEquals("INBOX", first.get("folder"));
        assertEquals(LocalDateTime.ofInstant(new Date(1_790_000_000_000L).toInstant(), ZoneId.systemDefault()), first.get("sent"), "local time");
        assertEquals(List.of(true, true, true), inboxSeen(), "mark_read marks each email as the loop reaches it");
        Map<String, Object> utc = find("INBOX", "Hello", "any").get(0);
        assertEquals(LocalDateTime.ofInstant(new Date(1_790_000_000_000L).toInstant(), java.time.ZoneOffset.UTC), utc.get("sent"), "UTC without local_time");
    }

    @Test void stepsInsideTheLoopActOnTheCurrentEmail() throws Exception {
        addFolder("Inv2026", "Looped");
        Map<String, Object> current = find("Inv2026", null, "any").get(0);
        ActionContext inLoop = context(current);
        Map<?, ?> e = (Map<?, ?>) pkg.get(inLoop, null, "INBOX", false, "mail", false).get("email");
        assertEquals("Looped", e.get("subject"), "id left empty: the loop's email, in its own folder");
        pkg.mark(inLoop, null, true, "INBOX", null, "mail");
        assertEquals(1, find("Inv2026", null, "read").size());
        Map<String, Object> saved = pkg.saveEml(inLoop, null, "", "INBOX", false, "mail", "");
        assertTrue(saved.get("path").toString().endsWith("Looped.eml"), "named from the subject, in the run's temporary folder");
        Map<String, Object> named = pkg.saveEml(inLoop, null, work.resolve("keep").toString() + "/", "INBOX", false, "mail", "case-7");
        assertEquals(work.resolve("keep").resolve("case-7.eml").toString(), named.get("path"));
        Map<String, Object> again = pkg.saveEml(inLoop, null, work.resolve("keep").toString() + "/", "INBOX", false, "mail", "case-7");
        assertEquals(work.resolve("keep").resolve("case-7 (2).eml").toString(), again.get("path"), "overwrite off keeps both");
        pkg.move(inLoop, null, "Done", "INBOX", true, "mail");
        assertEquals(0, find("Inv2026", null, "any").size());
        assertEquals("EMAIL.BAD_INPUT", assertThrows(ActionException.class, () -> pkg.get(ctx, null, "INBOX", false, "mail", false)).code(), "outside a loop the id is needed");
    }

    MimeMessage lastReceived() { MimeMessage[] r = mail.getReceivedMessages(); return r[r.length - 1]; }

    @Test void sendOptions() throws Exception {
        Path missing = work.resolve("nope.pdf");
        pkg.send(ctx, null, 587, "starttls", null, null, null, USER, "x@corp.test; bob@", null, null, "Käse", "<b>Hi</b>", false, List.of(missing.toString()),
                "normal", null, false, null, "mail", "rich", "<i>Regards</i>", true, true, "ISO-8859-1", 30);
        MimeMessage m = lastReceived();
        assertEquals("Käse", m.getSubject());
        Multipart mp = (Multipart) m.getContent();
        assertEquals(1, mp.getCount(), "the missing attachment was left out");
        BodyPart body = mp.getBodyPart(0);
        assertTrue(body.isMimeType("text/html"), "rich text is sent as HTML");
        assertTrue(body.getContentType().toLowerCase(Locale.ROOT).contains("iso-8859-1"));
        assertEquals("<b>Hi</b><br><br><i>Regards</i>", body.getContent());
        assertEquals("x@corp.test", ((InternetAddress) m.getRecipients(Message.RecipientType.TO)[0]).getAddress());
        assertEquals(1, m.getRecipients(Message.RecipientType.TO).length, "bob@ was left out");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("bob@")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("nope.pdf")));
        assertEquals("FILE.NOT_FOUND", assertThrows(ActionException.class, () -> pkg.send(ctx, null, 587, "starttls", null, null, null, USER, "x@corp.test", null, null, "s", "b", false,
                List.of(missing.toString()), "normal", null, false, null, "mail", "plain", null, false, false, "UTF-8", 30)).code());
        assertEquals("EMAIL.BAD_INPUT", assertThrows(ActionException.class, () -> EmailPackage.isHtml("markdown", false)).code());
        assertTrue(EmailPackage.isHtml("plain", true), "older steps with the html tick stay HTML");
    }

    @Test void replySubjectAndFooter() throws Exception {
        long id = ((Number) find("INBOX", "Hello", "any").get(0).get("id")).longValue();
        pkg.replyAll(ctx, id, "Thanks", false, false, null, null, 587, "starttls", null, null, null, null, "INBOX", null, null, "high", "help@corp.test", false, "mail",
                "plain", "Case 42 closed", "-- AP team", false, false, "UTF-8", 30);
        MimeMessage m = lastReceived();
        assertEquals("Case 42 closed", m.getSubject());
        assertEquals("Thanks\r\n\r\n-- AP team", ((String) m.getContent()).stripTrailing());
        assertEquals("High", m.getHeader("Importance", null));
        assertEquals("help@corp.test", ((InternetAddress) m.getReplyTo()[0]).getAddress());
    }
}
