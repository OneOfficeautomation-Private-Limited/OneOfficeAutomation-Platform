package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * X4: the Outlook desktop toolkit's scripts, run by PowerShell against a stand-in Outlook (folders, emails and
 * attachments made of PowerShell objects), so no real mailbox is touched.
 */
class OutlookDesktopPackageTest {

    /** A stand-in for Outlook.Application: an Inbox with two emails and an Invoices folder, Drafts, and a log of what was sent or saved. */
    static String fake(Path log) {
        return """
            $fakeLog = '%s'
            function New-Coll($arr) {
              $c = [pscustomobject]@{ list = (New-Object System.Collections.ArrayList) }
              foreach ($x in @($arr)) { if ($x -ne $null) { [void]$c.list.Add($x) } }
              $c | Add-Member ScriptProperty Count { $this.list.Count }
              $c | Add-Member ScriptMethod Item { param($i) $this.list[$i - 1] }
              $c | Add-Member ScriptMethod Add { param($x) [void]$this.list.Add($x); $x }
              $c | Add-Member ScriptMethod Sort { param($a, $b) }
              $c | Add-Member ScriptMethod Restrict { param($f) Add-Content -LiteralPath $fakeLog -Value ('restrict ' + $f); $l = $this.list; if ($f -like '*read" = 0*') { $l = @($l | Where-Object { $_.UnRead }) }; New-Coll $l }
              $c
            }
            function New-Att($name) { $a = [pscustomobject]@{ FileName = $name }; $a | Add-Member ScriptMethod SaveAsFile { param($p) Set-Content -LiteralPath $p -Value ('data of ' + $this.FileName) }; $a }
            function New-Draft($html) {
              $d = [pscustomobject]@{ EntryID = 'draft1'; To = ''; CC = ''; BCC = ''; Subject = ''; Body = ''; HTMLBody = $html; SendUsingAccount = $null; Attachments = (New-Coll @()) }
              $d | Add-Member ScriptMethod Send { Add-Content -LiteralPath $fakeLog -Value ('sent ' + (ConvertTo-Json -Compress -InputObject @{ to = $this.To; subject = $this.Subject; body = $this.Body; html = $this.HTMLBody; files = @($this.Attachments.list | ForEach-Object { $_ }); account = [string]$this.SendUsingAccount.SmtpAddress })) }
              $d | Add-Member ScriptMethod Save { Add-Content -LiteralPath $fakeLog -Value ('draft ' + $this.Subject) }
              $d
            }
            function New-Mail($id, $subject, $from, $unread, $files, $path) {
              $m = [pscustomobject]@{ EntryID = $id; Subject = $subject; SenderEmailAddress = $from; SenderEmailType = 'SMTP'; SenderName = 'Name ' + $from; To = 'me@acme.com'; CC = '';
                ReceivedTime = [datetime]'2026-10-20T09:30:00'; UnRead = $unread; MessageClass = 'IPM.Note'; Body = 'Body of ' + $subject; HTMLBody = '<p>' + $subject + '</p>'; Categories = '';
                Attachments = (New-Coll @($files | ForEach-Object { New-Att $_ })); Parent = [pscustomobject]@{ FolderPath = $path } }
              $m | Add-Member ScriptMethod Save { Add-Content -LiteralPath $fakeLog -Value ('saved ' + $this.EntryID + ' unread=' + $this.UnRead + ' categories=' + $this.Categories) }
              $m | Add-Member ScriptMethod Reply { New-Draft ('<p>original ' + $this.Subject + '</p>') }
              $m | Add-Member ScriptMethod Move { param($f) Add-Content -LiteralPath $fakeLog -Value ('moved ' + $this.EntryID + ' to ' + $f.Name); [pscustomobject]@{ EntryID = $this.EntryID + '-moved' } }
              $m
            }
            $mail1 = New-Mail 'id1' 'Invoice 42' 'ap@vendor.com' $true @('inv42.pdf', 'notes.txt') '\\\\me\\Inbox'
            $mail2 = New-Mail 'id2' 'Lunch' 'bob@acme.com' $false @() '\\\\me\\Inbox'
            $invoices = [pscustomobject]@{ Name = 'Invoices'; Items = (New-Coll @()); Folders = (New-Coll @()); UnReadItemCount = 0 }
            $inbox = [pscustomobject]@{ Name = 'Inbox'; Items = (New-Coll @($mail1, $mail2)); Folders = (New-Coll @($invoices)); UnReadItemCount = 1; Parent = $null }
            $drafts = [pscustomobject]@{ Name = 'Drafts'; Items = (New-Coll @()); Folders = (New-Coll @()); UnReadItemCount = 0 }
            $root = [pscustomobject]@{ Name = 'me'; Folders = (New-Coll @($inbox, $drafts)) }
            $inbox.Parent = $root
            $ns = [pscustomobject]@{ Stores = (New-Coll @()); Accounts = (New-Coll @([pscustomobject]@{ SmtpAddress = 'team@acme.com'; DisplayName = 'Team' })) }
            $ns | Add-Member ScriptMethod GetDefaultFolder { param($n) if ($n -eq 6) { $inbox } elseif ($n -eq 16) { $drafts } else { $null } }
            $ns | Add-Member ScriptMethod GetItemFromID { param($id) @($mail1, $mail2) | Where-Object { $_.EntryID -eq $id } | Select-Object -First 1 }
            $ns | Add-Member ScriptMethod CreateRecipient { param($n) $r = [pscustomobject]@{ Name = $n }; $r | Add-Member ScriptMethod Resolve { $false }; $r }
            $ol = [pscustomobject]@{ ns = $ns }
            $ol | Add-Member ScriptMethod GetNamespace { param($x) $this.ns }
            $ol | Add-Member ScriptMethod CreateItem { param($t) New-Draft '' }
            """.formatted(log.toString().replace("'", "''"));
    }

    static String code(Runnable r) { return assertThrows(ActionException.class, r::run).code(); }

    /** What the stand-in Outlook last sent (PowerShell's JSON escapes quotes and angle brackets, so it is parsed). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> lastSent(Path log) throws Exception {
        List<String> sent = Files.readAllLines(log, StandardCharsets.UTF_8).stream().filter(l -> l.startsWith("sent ")).toList();
        return (Map<String, Object>) JsonLite.parse(sent.get(sent.size() - 1).substring(5));
    }

    @Test @SuppressWarnings("unchecked")
    void scriptsWorkAgainstAStandInOutlook() throws Exception {
        assumeTrue(ProcessRunner.WINDOWS, "PowerShell and COM are Windows only");
        Path w = Files.createTempDirectory("ooa-outlook");
        Path log = w.resolve("fake.log");
        Files.writeString(log, "");
        String saved = OutlookDesktopPackage.factory;
        OutlookDesktopPackage.factory = fake(log);
        try {
            ActionContext ctx = AiGapsTest.ctx(w, new ArrayList<>());
            OutlookDesktopPackage o = new OutlookDesktopPackage();

            Map<String, Object> all = o.list(ctx, "Inbox", false, null, null, null, 50, null, 60);
            assertEquals(BigDecimal.valueOf(2), all.get("count"));
            Map<String, Object> first = (Map<String, Object>) ((List<Object>) all.get("emails")).get(0);
            assertEquals("id1", first.get("id"));
            assertEquals("ap@vendor.com", first.get("from"));
            assertEquals("2026-10-20T09:30:00", first.get("received"));
            assertEquals(Boolean.TRUE, first.get("unread"));
            // unread only goes to Outlook as a filter; the sender is matched in the script
            assertEquals(BigDecimal.ONE, o.list(ctx, "Inbox", true, "Invoice", null, null, 50, null, 60).get("count"));
            assertTrue(Files.readString(log).contains("restrict @SQL=\"urn:schemas:httpmail:read\" = 0 AND \"urn:schemas:httpmail:subject\" LIKE '%Invoice%'"));
            assertEquals(BigDecimal.ONE, o.list(ctx, "Inbox", false, null, "bob", null, 50, null, 60).get("count"));
            assertEquals(BigDecimal.ZERO, o.list(ctx, "Inbox/Invoices", false, null, null, null, 50, null, 60).get("count"));
            assertEquals("OUTLOOK.FOLDER_NOT_FOUND", code(() -> o.list(ctx, "Inbox/Nope", false, null, null, null, 50, null, 60)));
            assertEquals("OUTLOOK.MAILBOX_NOT_FOUND", code(() -> o.list(ctx, "Inbox", false, null, null, null, 50, "shared@acme.com", 60)));

            Map<String, Object> mail = (Map<String, Object>) o.get(ctx, "id1").get("email");
            assertEquals("Body of Invoice 42", mail.get("body"));
            assertEquals(List.of("inv42.pdf", "notes.txt"), mail.get("attachment_names"));
            assertEquals("OUTLOOK.NOT_FOUND", code(() -> o.get(ctx, "nope")));

            List<Object> files = (List<Object>) o.saveAttachments(ctx, "id1", "att", "*.pdf").get("files");
            assertEquals(1, files.size());
            assertEquals("data of inv42.pdf", Files.readString(Path.of((String) files.get(0))).strip());
            o.saveAttachments(ctx, "id1", "att", "*.pdf");
            assertTrue(Files.exists(w.resolve("att/inv42 (1).pdf")), "an existing file is kept and the new one numbered");

            // the text goes in a JSON file, so quotes, $ and new lines arrive as written
            Files.writeString(w.resolve("report.pdf"), "x");
            o.send(ctx, "a@acme.com", null, null, "It's $5 \"now\"", "Line 1\nLine 2", false, List.of("report.pdf"), "team@acme.com", false);
            Map<String, Object> sent = lastSent(log);
            assertEquals("It\'s $5 \"now\"", sent.get("subject"));
            assertEquals("Line 1\nLine 2", sent.get("body"));
            assertEquals("team@acme.com", sent.get("account"));
            assertTrue(String.valueOf(sent.get("files")).contains("report.pdf"), String.valueOf(sent.get("files")));
            assertEquals("OUTLOOK.BAD_ACCOUNT", code(() -> o.send(ctx, "a@acme.com", null, null, "s", "b", false, null, "other@acme.com", false)));
            assertEquals("draft1", o.send(ctx, "a@acme.com", null, null, "Check me", "b", false, null, null, true).get("draft_id"));
            assertTrue(Files.readString(log).contains("draft Check me"));

            o.reply(ctx, "id1", "Paid <today>", false, false);
            assertEquals("<p>Paid &lt;today&gt;</p><p>original Invoice 42</p>", lastSent(log).get("html"), "the reply text is HTML-encoded above the original");

            o.mark(ctx, "id1", true, "Processed");
            assertTrue(Files.readString(log).contains("saved id1 unread=False categories=Processed"));
            assertEquals("id1-moved", o.move(ctx, "id1", "Inbox/Invoices", null).get("id"));
            assertTrue(Files.readString(log).contains("moved id1 to Invoices"));

            List<Object> folders = (List<Object>) o.getFolders(ctx, null).get("folders");
            assertEquals(List.of("Inbox", "Inbox/Invoices", "Drafts"), folders.stream().map(f -> ((Map<String, Object>) f).get("path")).toList());
        } finally { OutlookDesktopPackage.factory = saved; }
    }

    @Test
    void filtersAndErrors() {
        assertEquals("", OutlookDesktopPackage.filter(false, null, null));
        assertEquals("@SQL=\"urn:schemas:httpmail:subject\" LIKE '%O''Neil%'", OutlookDesktopPackage.filter(false, "O'Neil%", null));
        assertTrue(OutlookDesktopPackage.filter(false, null, "2026-10-01T00:00:00").startsWith("@SQL=\"urn:schemas:httpmail:datereceived\" >= '2026-"));
        assertEquals("OUTLOOK.NOT_INSTALLED", OutlookDesktopPackage.error("Retrieving the COM class factory for component failed: 80040154").code());
        assertEquals("OUTLOOK.FOLDER_NOT_FOUND", OutlookDesktopPackage.error("OOA:FOLDER_NOT_FOUND:Inbox/X").code());
        // nothing the agent passes is pasted into the script code
        OutlookDesktopPackage.Script s = OutlookDesktopPackage.sendScript("a@b.c", null, null, "'; Remove-Item C:\\ -Recurse; '", "b", false, List.of(), null, false);
        assertFalse(s.full().contains("Remove-Item"));
        assertEquals("'; Remove-Item C:\\ -Recurse; '", s.input().get("subject"));
    }
}
