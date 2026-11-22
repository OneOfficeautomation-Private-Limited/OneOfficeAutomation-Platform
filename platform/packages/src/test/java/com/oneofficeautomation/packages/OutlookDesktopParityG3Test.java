package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * G3 (AA parity, 22 Nov 2026), Outlook desktop against the stand-in Outlook of {@link OutlookDesktopPackageTest}: the
 * mailbox named once, delete/move/save every email the filters find, save one email, a folder check, copies, files and
 * HTML on reply and forward, attachments saved over, and the loop's new filters.
 */
class OutlookDesktopParityG3Test {

    /** The stand-in, plus what G3 needs: Forward, Delete and SaveAs on the emails, a shared mailbox named Team. */
    static String fake(Path log) {
        return OutlookDesktopPackageTest.fake(log) + """
            foreach ($mm in @($mail1, $mail2)) {
              $mm | Add-Member ScriptMethod Forward { New-Draft ('<p>original ' + $this.Subject + '</p>') }
              $mm | Add-Member ScriptMethod Delete { Add-Content -LiteralPath $fakeLog -Value ('deleted ' + $this.EntryID) }
              $mm | Add-Member ScriptMethod SaveAs { param($p, $t) Set-Content -LiteralPath $p -Value ('mail ' + $this.EntryID + ' type ' + $t) }
            }
            $teamRoot = [pscustomobject]@{ Name = 'Team'; Folders = (New-Coll @([pscustomobject]@{ Name = 'Inbox'; Items = (New-Coll @()); Folders = (New-Coll @()); UnReadItemCount = 0 })) }
            $teamRoot | Add-Member ScriptMethod GetRootFolder { $this }
            $teamRoot | Add-Member NoteProperty DisplayName 'Team'
            [void]$ns.Stores.list.Add($teamRoot)
            """;
    }

    static String code(Runnable r) { return assertThrows(ActionException.class, r::run).code(); }

    @Test @SuppressWarnings("unchecked")
    void g3StepsWorkAgainstAStandInOutlook() throws Exception {
        assumeTrue(ProcessRunner.WINDOWS, "PowerShell and COM are Windows only");
        Path w = Files.createTempDirectory("ooa-outlook-g3");
        Path log = w.resolve("fake.log");
        Files.writeString(log, "");
        String saved = OutlookDesktopPackage.factory;
        OutlookDesktopPackage.factory = fake(log);
        try {
            ActionContext ctx = AiGapsTest.ctx(w, new ArrayList<>());
            OutlookDesktopPackage o = new OutlookDesktopPackage();

            // the mailbox named once: checked, then used by the steps that leave theirs empty
            assertEquals("OUTLOOK.MAILBOX_NOT_FOUND", code(() -> o.connect(ctx, "nobody@acme.com", false)));
            o.connect(ctx, "Team", false);
            assertEquals(BigDecimal.ZERO, o.list(ctx, "Inbox", false, null, null, null, 50, null, 60).get("count"), "the Team mailbox's empty Inbox");
            assertTrue(o.folderExists(ctx, "Inbox", null));
            assertFalse(o.folderExists(ctx, "Inbox/Invoices", null), "Team has no Invoices folder");
            o.disconnect(ctx);
            assertEquals(BigDecimal.valueOf(2), o.list(ctx, "Inbox", false, null, null, null, 50, null, 60).get("count"), "back to the user's own");
            assertTrue(o.folderExists(ctx, "Inbox/Invoices", null));

            // many emails at once, picked by the filters
            assertEquals(BigDecimal.ONE, o.deleteAll(ctx, "Inbox", true, null, null, null, null, false, 500, null).get("count"));
            assertTrue(Files.readString(log).contains("deleted id1"));
            Map<String, Object> moved = o.moveAll(ctx, "Inbox", "Inbox/Invoices", false, null, "bob", null, null, false, 500, null, null);
            assertEquals(List.of("id2-moved"), moved.get("ids"));
            List<Object> files = (List<Object>) o.saveAll(ctx, "Inbox", "mails", "msg", false, false, null, null, null, null, false, 500, null).get("files");
            assertEquals(2, files.size());
            assertTrue(files.get(0).toString().endsWith("Invoice 42.msg"), files.toString());
            assertTrue(Files.readString(Path.of(files.get(0).toString())).contains("type 3"), "saved as .msg");
            o.saveAll(ctx, "Inbox", "mails", "msg", false, false, null, null, null, null, false, 500, null);
            assertTrue(Files.exists(w.resolve("mails/Invoice 42 (1).msg")), "kept and numbered");
            assertEquals("OUTLOOK.BAD_FORMAT", code(() -> o.saveAll(ctx, "Inbox", "mails", "pdf", false, false, null, null, null, null, false, 500, null)));

            // one email
            String one = String.valueOf(o.saveEmail(ctx, "id2", "one", "txt", "lunch.txt", false).get("path"));
            assertTrue(one.endsWith("lunch.txt") && Files.readString(Path.of(one)).contains("type 0"), one);
            o.saveEmail(ctx, "id2", "one", "txt", "lunch.txt", true);
            assertFalse(Files.exists(w.resolve("one/lunch (1).txt")), "overwrite replaces the file");
            assertEquals("OUTLOOK.BAD_NAME", code(() -> o.saveEmail(ctx, "id2", "one", "txt", "a/b.txt", false)));

            // reply and forward with copies, files and HTML
            Files.writeString(w.resolve("receipt.pdf"), "x");
            o.reply(ctx, "id1", "<b>Paid</b>", false, false, "boss@acme.com", null, List.of("receipt.pdf"), true);
            Map<String, Object> sent = OutlookDesktopPackageTest.lastSent(log);
            assertEquals("<b>Paid</b><p>original Invoice 42</p>", sent.get("html"), "the HTML note as written, above the original");
            assertTrue(String.valueOf(sent.get("files")).contains("receipt.pdf"));
            o.forward(ctx, "id2", "x@acme.com", "FYI", false, null, null, null, false);
            assertEquals("x@acme.com", OutlookDesktopPackageTest.lastSent(log).get("to"));
            assertEquals("FILE.NOT_FOUND", code(() -> o.forward(ctx, "id2", "x@acme.com", "FYI", false, null, null, List.of("missing.pdf"), false)));

            // attachments saved over the existing files
            o.saveAttachments(ctx, "id1", "att", "*.pdf", false);
            o.saveAttachments(ctx, "id1", "att", "*.pdf", true);
            assertFalse(Files.exists(w.resolve("att/inv42 (1).pdf")), "replaced, not numbered");

            // the loop: read ones only, and the filters Outlook gets
            List<Object> read = new ArrayList<>();
            o.emails(ctx, "Inbox", false, null, null, null, 50, false, null, "2026-11-01T00:00:00", true, "read").forEach(read::add);
            assertEquals(1, read.size());
            assertEquals("id2", ((Map<String, Object>) read.get(0)).get("id"));
            assertTrue(Files.readString(log).contains("\"urn:schemas:httpmail:hasattachment\" = 1"));
            assertTrue(Files.readString(log).contains("\"urn:schemas:httpmail:datereceived\" < '"));
        } finally { OutlookDesktopPackage.factory = saved; }
    }

    @Test void filtersForTheNewChoices() {
        assertEquals("", OutlookDesktopPackage.filter(false, null, null, null, false));
        assertEquals("@SQL=\"urn:schemas:httpmail:hasattachment\" = 1", OutlookDesktopPackage.filter(false, null, null, null, true));
        assertEquals(OutlookDesktopPackage.filter(true, "x", null), OutlookDesktopPackage.filter(true, "x", null, null, false), "the old filter is unchanged");
    }
}
