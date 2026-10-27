package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Drives the Outlook installed on a Windows runner through its COM interface, with the mail profile of the runner's
 * user: no server address or password, and shared mailboxes the profile has open. For on-premises Exchange and for
 * mailboxes Outlook already reaches; Microsoft 365 mailboxes can also use Microsoft 365 (Graph) or Email (IMAP).
 * The inputs go to the PowerShell script in a JSON file, never into the script code or the command line, and the
 * script answers in JSON.
 */
@OoaPackage(id = "ooa.outlook_desktop", name = "Outlook (desktop, needs Outlook)", version = "1.0.0",
        description = "Find, read, send, reply to, forward, move and file emails with the Outlook installed on the runner, using its mail profile.")
public final class OutlookDesktopPackage {

    /** How the script gets its Outlook; tests put a stand-in here. */
    static String factory = "$ol = New-Object -ComObject Outlook.Application\n";

    static final String PRELUDE = """
        $ErrorActionPreference = 'Stop'
        [Console]::OutputEncoding = [Text.Encoding]::UTF8
        $in = Get-Content -Raw -Encoding UTF8 -LiteralPath $args[0] | ConvertFrom-Json
        """;

    /** Folder and mailbox lookup, and an email as a dictionary; shared by every action's script. */
    static final String HELPERS = """
        $ns = $ol.GetNamespace('MAPI')
        $wellKnown = @{ 'inbox' = 6; 'sent items' = 5; 'sent' = 5; 'drafts' = 16; 'deleted items' = 3; 'outbox' = 4; 'junk email' = 23; 'junk' = 23 }
        function Find-Child($parent, $name) {
          $fs = $parent.Folders
          for ($i = 1; $i -le $fs.Count; $i++) { $c = $fs.Item($i); if ($c.Name -eq $name) { return $c } }
          return $null
        }
        function Get-Root($mailbox) {
          if (-not $mailbox) { return $ns.GetDefaultFolder(6).Parent }
          $ss = $ns.Stores
          for ($i = 1; $i -le $ss.Count; $i++) { $s = $ss.Item($i); if ($s.DisplayName -eq $mailbox) { return $s.GetRootFolder() } }
          $r = $ns.CreateRecipient($mailbox)
          if ($r.Resolve()) { try { return $ns.GetSharedDefaultFolder($r, 6).Parent } catch { } }
          throw "OOA:MAILBOX_NOT_FOUND:$mailbox"
        }
        function Get-Folder($path, $mailbox) {
          $parts = @(([string]$path) -split '[\\\\/]' | Where-Object { $_ -ne '' })
          if ($parts.Count -eq 0) { $parts = @('Inbox') }
          $first = $parts[0].ToLower()
          $f = $null
          if (-not $mailbox -and $wellKnown.ContainsKey($first)) { $f = $ns.GetDefaultFolder($wellKnown[$first]) }
          else {
            $f = Find-Child (Get-Root $mailbox) $parts[0]
            if (-not $f -and $mailbox -and $wellKnown.ContainsKey($first)) {
              $r = $ns.CreateRecipient($mailbox); [void]$r.Resolve()
              try { $f = $ns.GetSharedDefaultFolder($r, $wellKnown[$first]) } catch { }
            }
          }
          for ($i = 1; $f -and $i -lt $parts.Count; $i++) { $f = Find-Child $f $parts[$i] }
          if (-not $f) { throw "OOA:FOLDER_NOT_FOUND:$path" }
          return $f
        }
        function Get-Mail($id) {
          try { $m = $ns.GetItemFromID($id) } catch { throw "OOA:NOT_FOUND:$id" }
          if (-not $m) { throw "OOA:NOT_FOUND:$id" }
          return $m
        }
        function Sender-Address($m) {
          if ($m.SenderEmailType -eq 'EX') { try { $u = $m.Sender.GetExchangeUser(); if ($u) { return $u.PrimarySmtpAddress } } catch { } }
          return [string]$m.SenderEmailAddress
        }
        function Mail-Summary($m) {
          [ordered]@{ id = $m.EntryID; subject = [string]$m.Subject; from = (Sender-Address $m); from_name = [string]$m.SenderName; to = [string]$m.To;
            received = $(if ($m.ReceivedTime) { ([datetime]$m.ReceivedTime).ToString('yyyy-MM-ddTHH:mm:ss') } else { '' }); unread = [bool]$m.UnRead;
            attachments = [int]$m.Attachments.Count; folder = [string]$m.Parent.FolderPath }
        }
        function Html-Text($t) { [System.Net.WebUtility]::HtmlEncode([string]$t) -replace "`r?`n", '<br>' }
        function Out-Json($o) { ConvertTo-Json -InputObject $o -Depth 6 -Compress }
        """;

    record Script(String body, Map<String, Object> input) {
        String full() { return PRELUDE + factory + HELPERS + "try {\n" + body + "\n} catch {\n  [Console]::Error.WriteLine($_.Exception.Message)\n  exit 3\n}\n"; }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> outlook(ActionContext ctx, Script s, int timeout) {
        if (!ProcessRunner.WINDOWS) throw new ActionException("SYSTEM.WINDOWS_ONLY", "Desktop Outlook automation needs Windows with Outlook installed",
                "Use Email (IMAP and SMTP) or Microsoft 365 on other runners.");
        Path script = null, input = null;
        try {
            script = Files.createTempFile("ooa-outlook-", ".ps1");
            input = Files.createTempFile("ooa-outlook-", ".json");
            Files.writeString(script, s.full(), StandardCharsets.UTF_8);
            Files.writeString(input, JsonLite.write(s.input()), StandardCharsets.UTF_8);
            ProcessRunner.Result r = ProcessRunner.run(ctx, List.of("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.toString(), input.toString()),
                    ctx.workDir(), null, timeout, Map.of(), StandardCharsets.UTF_8);
            if (r.exitCode() != 0) throw error(r.stderr().isBlank() ? r.stdout() : r.stderr());
            String out = r.stdout().strip();
            if (out.isEmpty()) return Map.of();
            Object v = JsonLite.parse(out.substring(out.indexOf('{')));
            return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true);
        } finally {
            for (Path p : new Path[]{script, input}) if (p != null) try { Files.deleteIfExists(p); } catch (IOException ignored) { }
        }
    }

    static ActionException error(String err) {
        int at = err.indexOf("OOA:");
        if (at >= 0) {
            String[] p = err.substring(at + 4).strip().split(":", 2);
            String detail = p.length > 1 ? p[1].strip() : "";
            return switch (p[0]) {
                case "FOLDER_NOT_FOUND" -> new ActionException("OUTLOOK.FOLDER_NOT_FOUND", "No folder " + detail + " in the mailbox", "Use Get folders to see the folder paths, e.g. Inbox/Invoices.");
                case "MAILBOX_NOT_FOUND" -> new ActionException("OUTLOOK.MAILBOX_NOT_FOUND", "The mailbox " + detail + " is not open in this Outlook profile", "Add it to the runner user's Outlook, or check its name.");
                case "NOT_FOUND" -> new ActionException("OUTLOOK.NOT_FOUND", "No email with that id", "The email may have been moved or deleted; ids change when an email is moved.");
                case "BAD_ACCOUNT" -> new ActionException("OUTLOOK.BAD_ACCOUNT", "No account " + detail + " in this Outlook profile", "Leave From account empty to send from the default account.");
                case "NO_FILE" -> new ActionException("FILE.NOT_FOUND", "Attachment not found: " + detail, "Check the path.");
                default -> new ActionException("OUTLOOK.FAILED", "Outlook reported an error: " + ApplicationPackage.tail(err), null);
            };
        }
        if (err.contains("80040154") || err.contains("Retrieving the COM class factory") || err.contains("Cannot load COM type"))
            return new ActionException("OUTLOOK.NOT_INSTALLED", "Microsoft Outlook is not installed on this runner", "Install Outlook with a mail profile for the runner's user, or use Email or Microsoft 365.");
        return new ActionException("OUTLOOK.FAILED", "Outlook reported an error: " + ApplicationPackage.tail(err), "Check that Outlook has a mail profile for the runner's user and is not showing a dialog.", true);
    }

    private static Map<String, Object> in(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) if (kv[i + 1] != null) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static String text(String s) { return s == null ? "" : s; }

    private static List<String> files(ActionContext ctx, List<Object> paths) {
        List<String> out = new ArrayList<>();
        if (paths != null) for (Object o : paths) {
            Path p = ctx.resolvePath(Values.text(o)).toAbsolutePath();
            if (!Files.isRegularFile(p)) throw new ActionException("FILE.NOT_FOUND", "Attachment not found: " + p, "Check the path.");
            out.add(p.toString());
        }
        return out;
    }

    /** A DASL filter for Items.Restrict: read state, subject words and a received-after date (UTC). */
    static String filter(boolean unreadOnly, String subject, Object after) {
        List<String> f = new ArrayList<>();
        if (unreadOnly) f.add("\"urn:schemas:httpmail:read\" = 0");
        if (subject != null && !subject.isBlank()) f.add("\"urn:schemas:httpmail:subject\" LIKE '%" + subject.strip().replace("'", "''").replace("%", "") + "%'");
        if (after != null && !Values.text(after).isBlank()) {
            java.time.Instant t = Values.toDateTime(after).atZone(java.time.ZoneId.systemDefault()).toInstant();
            f.add("\"urn:schemas:httpmail:datereceived\" >= '" + java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(java.time.ZoneOffset.UTC).format(t) + "'");
        }
        return f.isEmpty() ? "" : "@SQL=" + String.join(" AND ", f);
    }

    private static final String D_MAILBOX = "Another mailbox open in the runner's Outlook profile (its name or address), e.g. a shared mailbox; empty for the user's own.";
    private static final String D_ID = "The email's id from Find emails.";

    // ------------------------------------------------------------------------------------------------

    static Script listScript(String folder, String mailbox, boolean unreadOnly, String subject, String from, Object after, int max) {
        String body = """
              $f = Get-Folder $in.folder $in.mailbox
              $items = $f.Items
              if ($in.filter) { $items = $items.Restrict($in.filter) }
              $items.Sort('[ReceivedTime]', $true)
              $out = New-Object System.Collections.ArrayList
              for ($i = 1; $i -le $items.Count -and $out.Count -lt $in.max; $i++) {
                $m = $items.Item($i)
                if ($m.MessageClass -notlike 'IPM.Note*') { continue }
                if ($in.from -and -not ((Sender-Address $m) -like ('*' + $in.from + '*') -or ([string]$m.SenderName) -like ('*' + $in.from + '*'))) { continue }
                [void]$out.Add((Mail-Summary $m))
              }
              Out-Json @{ emails = @($out) }
            """;
        return new Script(body, in("folder", text(folder), "mailbox", text(mailbox), "filter", filter(unreadOnly, subject, after), "from", text(from), "max", max));
    }

    @Action(id = "list", name = "Find emails", category = "Mailbox", idempotent = true, timeoutSeconds = 300, capabilities = "desktop",
            description = "List the newest emails in an Outlook folder, optionally only unread ones, by subject, by sender or received after a date.",
            panel = "folder, unread_only, subject_contains, from_contains, received_after, max | mailbox, timeout_seconds")
    @Output(name = "emails", type = OoaType.LIST, description = "Emails, newest first: id, subject, from, from_name, to, received, unread, attachments, folder.")
    @Output(name = "count", type = OoaType.NUMBER, description = "How many were found.")
    public Map<String, Object> list(ActionContext ctx,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"Inbox\"", example = "Inbox/Invoices", description = "Folder path: Inbox, Sent Items, Drafts, or a folder below them, e.g. Inbox/Invoices.") String folder,
            @Input(name = "unread_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only unread emails.") boolean unreadOnly,
            @Input(name = "subject_contains", type = OoaType.STRING, required = false, description = "Only emails whose subject contains this text.") String subject,
            @Input(name = "from_contains", type = OoaType.STRING, required = false, description = "Only emails whose sender address or name contains this text.") String from,
            @Input(name = "received_after", type = OoaType.DATETIME, required = false, description = "Only emails received after this date and time.") Object after,
            @Input(name = "max", type = OoaType.NUMBER, required = false, defaultValue = "50", min = 1, max = 5000, description = "At most this many emails.") int max,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, description = D_MAILBOX) String mailbox,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "120", description = "Maximum time.") int timeout) {
        Object emails = outlook(ctx, listScript(folder, mailbox, unreadOnly, subject, from, after, max), timeout).getOrDefault("emails", List.of());
        List<Object> l = emails instanceof List<?> x ? new ArrayList<>(x) : List.of(emails);
        return Map.of("emails", l, "count", BigDecimal.valueOf(l.size()));
    }

    static Script getScript(String id) {
        return new Script("""
              $m = Get-Mail $in.id
              $r = Mail-Summary $m
              $r.cc = [string]$m.CC
              $r.body = [string]$m.Body
              $r.html_body = [string]$m.HTMLBody
              $r.categories = [string]$m.Categories
              $names = New-Object System.Collections.ArrayList
              for ($i = 1; $i -le $m.Attachments.Count; $i++) { [void]$names.Add([string]$m.Attachments.Item($i).FileName) }
              $r.attachment_names = @($names)
              Out-Json $r
            """, in("id", id));
    }

    @Action(id = "get", name = "Read email", category = "Mailbox", idempotent = true, capabilities = "desktop",
            description = "Read one email: sender, recipients, subject, text and HTML body and attachment names.", panel = "id")
    @Output(name = "email", type = OoaType.DICTIONARY, description = "id, subject, from, from_name, to, cc, received, unread, body, html_body, categories, attachment_names, folder.")
    public Map<String, Object> get(ActionContext ctx, @Input(name = "id", type = OoaType.STRING, description = D_ID) String id) {
        return Map.of("email", outlook(ctx, getScript(id), 120));
    }

    static Script sendScript(String to, String cc, String bcc, String subject, String body, boolean html, List<String> attachments, String account, boolean draft) {
        return new Script("""
              $m = $ol.CreateItem(0)
              $m.To = $in.to; if ($in.cc) { $m.CC = $in.cc }; if ($in.bcc) { $m.BCC = $in.bcc }
              $m.Subject = $in.subject
              if ($in.html) { $m.HTMLBody = $in.body } else { $m.Body = $in.body }
              foreach ($a in @($in.attachments)) { if ($a) { if (-not (Test-Path -LiteralPath $a)) { throw "OOA:NO_FILE:$a" }; [void]$m.Attachments.Add($a) } }
              if ($in.account) {
                $acc = $null
                for ($i = 1; $i -le $ns.Accounts.Count; $i++) { $x = $ns.Accounts.Item($i); if ($x.SmtpAddress -eq $in.account -or $x.DisplayName -eq $in.account) { $acc = $x } }
                if (-not $acc) { throw "OOA:BAD_ACCOUNT:$($in.account)" }
                $m.SendUsingAccount = $acc
              }
              if ($in.draft) { $m.Save(); Out-Json @{ id = $m.EntryID; sent = $false } } else { $m.Send(); Out-Json @{ id = ''; sent = $true } }
            """, in("to", text(to), "cc", text(cc), "bcc", text(bcc), "subject", text(subject), "body", text(body), "html", html, "attachments", attachments, "account", text(account), "draft", draft));
    }

    @Action(id = "send", name = "Send email", category = "Send", timeoutSeconds = 120, capabilities = "desktop",
            description = "Send an email from Outlook, or keep it in Drafts for someone to check, with attachments and optionally from another account of the profile.",
            panel = "to, cc, subject, body, html, attachments | bcc, from_account, save_as_draft")
    @Output(name = "draft_id", type = OoaType.STRING, description = "The draft's id when it was kept as a draft, else empty.")
    public Map<String, Object> send(ActionContext ctx,
            @Input(name = "to", type = OoaType.STRING, example = "ap@acme.com; finance@acme.com", description = "Recipients, separated by semicolons.") String to,
            @Input(name = "cc", type = OoaType.STRING, required = false, description = "Copy to.") String cc,
            @Input(name = "bcc", type = OoaType.STRING, required = false, description = "Blind copy to.") String bcc,
            @Input(name = "subject", type = OoaType.STRING, description = "Subject.") String subject,
            @Input(name = "body", type = OoaType.STRING, required = false, description = "The message.") String body,
            @Input(name = "html", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "The message is HTML.") boolean html,
            @Input(name = "attachments", type = OoaType.LIST, required = false, description = "Files to attach.") List<Object> attachments,
            @Input(name = "from_account", type = OoaType.STRING, required = false, description = "Send from this account of the Outlook profile (its address); empty for the default.") String account,
            @Input(name = "save_as_draft", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Keep it in Drafts instead of sending.") boolean draft) {
        if (to == null || to.isBlank()) throw new ActionException("OUTLOOK.NO_RECIPIENT", "Give at least one recipient");
        Map<String, Object> r = outlook(ctx, sendScript(to, cc, bcc, subject, body, html, files(ctx, attachments), account, draft), 120);
        return Map.of("draft_id", Values.text(r.getOrDefault("id", "")));
    }

    static Script replyScript(String id, String body, boolean all, String forwardTo, boolean draft) {
        return new Script("""
              $m = Get-Mail $in.id
              if ($in.forward_to) { $r = $m.Forward(); $r.To = $in.forward_to } elseif ($in.all) { $r = $m.ReplyAll() } else { $r = $m.Reply() }
              if ($in.body) { $r.HTMLBody = '<p>' + (Html-Text $in.body) + '</p>' + $r.HTMLBody }
              if ($in.draft) { $r.Save(); Out-Json @{ id = $r.EntryID } } else { $r.Send(); Out-Json @{ id = '' } }
            """, in("id", id, "body", text(body), "all", all, "forward_to", forwardTo, "draft", draft));
    }

    @Action(id = "reply", name = "Reply to email", category = "Send", timeoutSeconds = 120, capabilities = "desktop",
            description = "Reply to the sender (or everyone) above the original message; send it or keep it in Drafts.", panel = "id, body, reply_all | save_as_draft")
    @Output(name = "draft_id", type = OoaType.STRING, description = "The draft's id when it was kept as a draft, else empty.")
    public Map<String, Object> reply(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, description = D_ID) String id,
            @Input(name = "body", type = OoaType.STRING, required = false, description = "Your reply (text).") String body,
            @Input(name = "reply_all", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Reply to everyone on the email.") boolean all,
            @Input(name = "save_as_draft", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Keep it in Drafts instead of sending.") boolean draft) {
        return Map.of("draft_id", Values.text(outlook(ctx, replyScript(id, body, all, null, draft), 120).getOrDefault("id", "")));
    }

    @Action(id = "forward", name = "Forward email", category = "Send", timeoutSeconds = 120, capabilities = "desktop",
            description = "Forward an email with its attachments, with a note above it; send it or keep it in Drafts.", panel = "id, to, body | save_as_draft")
    @Output(name = "draft_id", type = OoaType.STRING, description = "The draft's id when it was kept as a draft, else empty.")
    public Map<String, Object> forward(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, description = D_ID) String id,
            @Input(name = "to", type = OoaType.STRING, description = "Recipients, separated by semicolons.") String to,
            @Input(name = "body", type = OoaType.STRING, required = false, description = "A note above the forwarded email.") String body,
            @Input(name = "save_as_draft", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Keep it in Drafts instead of sending.") boolean draft) {
        if (to == null || to.isBlank()) throw new ActionException("OUTLOOK.NO_RECIPIENT", "Give at least one recipient");
        return Map.of("draft_id", Values.text(outlook(ctx, replyScript(id, body, false, to, draft), 120).getOrDefault("id", "")));
    }

    static Script saveAttachmentsScript(String id, String folder, String pattern) {
        return new Script("""
              $m = Get-Mail $in.id
              New-Item -ItemType Directory -Force -Path $in.folder | Out-Null
              $saved = New-Object System.Collections.ArrayList
              for ($i = 1; $i -le $m.Attachments.Count; $i++) {
                $a = $m.Attachments.Item($i)
                $name = [string]$a.FileName
                if (-not $name -or ($in.pattern -and -not ($name -like $in.pattern))) { continue }
                $name = $name -replace '[\\\\/:*?"<>|]', '_'
                $target = Join-Path $in.folder $name
                $n = 1
                while (Test-Path -LiteralPath $target) { $target = Join-Path $in.folder ([IO.Path]::GetFileNameWithoutExtension($name) + " ($n)" + [IO.Path]::GetExtension($name)); $n++ }
                $a.SaveAsFile($target)
                [void]$saved.Add($target)
              }
              Out-Json @{ files = @($saved) }
            """, in("id", id, "folder", folder, "pattern", text(pattern)));
    }

    @Action(id = "save_attachments", name = "Save attachments", category = "Mailbox", capabilities = {"desktop", "filesystem"},
            description = "Save an email's attachments to a folder, optionally only those matching a name pattern; existing files are kept and new ones numbered.",
            panel = "id, folder, name_pattern")
    @Output(name = "files", type = OoaType.LIST, description = "The saved files.")
    public Map<String, Object> saveAttachments(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, description = D_ID) String id,
            @Input(name = "folder", type = OoaType.FILE, description = "Folder to save into.") String folder,
            @Input(name = "name_pattern", type = OoaType.STRING, required = false, example = "*.pdf", description = "Only attachments whose name matches, e.g. *.pdf.") String pattern) {
        Object files = outlook(ctx, saveAttachmentsScript(id, ctx.resolvePath(folder).toAbsolutePath().toString(), pattern), 300).getOrDefault("files", List.of());
        return Map.of("files", files instanceof List<?> l ? new ArrayList<>(l) : List.of(files));
    }

    static Script moveScript(String id, String folder, String mailbox) {
        return new Script("""
              $m = Get-Mail $in.id
              $moved = $m.Move((Get-Folder $in.folder $in.mailbox))
              Out-Json @{ id = $moved.EntryID }
            """, in("id", id, "folder", folder, "mailbox", text(mailbox)));
    }

    @Action(id = "move", name = "Move email", category = "Mailbox", capabilities = "desktop",
            description = "Move an email to another folder, e.g. Inbox/Done. The email gets a new id.", panel = "id, folder | mailbox")
    @Output(name = "id", type = OoaType.STRING, description = "The email's new id.")
    public Map<String, Object> move(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, description = D_ID) String id,
            @Input(name = "folder", type = OoaType.STRING, example = "Inbox/Done", description = "Folder path to move it to.") String folder,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, description = D_MAILBOX) String mailbox) {
        return Map.of("id", Values.text(outlook(ctx, moveScript(id, folder, mailbox), 120).getOrDefault("id", "")));
    }

    static Script markScript(String id, boolean read, String category) {
        return new Script("""
              $m = Get-Mail $in.id
              $m.UnRead = -not $in.read
              if ($in.category) { $c = @(([string]$m.Categories) -split ',\\s*' | Where-Object { $_ }); if ($c -notcontains $in.category) { $m.Categories = (@($c) + $in.category) -join ', ' } }
              $m.Save()
              Out-Json @{ ok = $true }
            """, in("id", id, "read", read, "category", text(category)));
    }

    @Action(id = "mark", name = "Mark read or unread", category = "Mailbox", capabilities = "desktop",
            description = "Mark an email as read or unread, and optionally add an Outlook category to it.", panel = "id, read, category")
    public Map<String, Object> mark(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, description = D_ID) String id,
            @Input(name = "read", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "true marks it read, false unread.") boolean read,
            @Input(name = "category", type = OoaType.STRING, required = false, example = "Processed", description = "An Outlook category to add, e.g. Processed.") String category) {
        outlook(ctx, markScript(id, read, category), 120);
        return Map.of();
    }

    @Action(id = "delete", name = "Delete email", category = "Mailbox", capabilities = "desktop",
            description = "Delete an email (Outlook moves it to Deleted Items).", panel = "id")
    public Map<String, Object> delete(ActionContext ctx, @Input(name = "id", type = OoaType.STRING, description = D_ID) String id) {
        outlook(ctx, new Script("  (Get-Mail $in.id).Delete()\n  Out-Json @{ ok = $true }", in("id", id)), 120);
        return Map.of();
    }

    static Script foldersScript(String mailbox) {
        return new Script("""
              $out = New-Object System.Collections.ArrayList
              function Walk($f, $path, $depth) {
                $fs = $f.Folders
                for ($i = 1; $i -le $fs.Count; $i++) {
                  $c = $fs.Item($i); $p = $(if ($path) { $path + '/' + $c.Name } else { $c.Name })
                  [void]$out.Add([ordered]@{ path = $p; emails = [int]$c.Items.Count; unread = [int]$c.UnReadItemCount })
                  if ($depth -lt 6 -and $out.Count -lt 2000) { Walk $c $p ($depth + 1) }
                }
              }
              Walk (Get-Root $in.mailbox) '' 1
              Out-Json @{ folders = @($out) }
            """, in("mailbox", text(mailbox)));
    }

    @Action(id = "get_folders", name = "Get folders", category = "Mailbox", idempotent = true, timeoutSeconds = 300, capabilities = "desktop",
            description = "List the folders of the mailbox with how many emails and unread emails each has.", panel = "mailbox")
    @Output(name = "folders", type = OoaType.LIST, description = "Folders: path (as Move email and Find emails take it), emails, unread.")
    public Map<String, Object> getFolders(ActionContext ctx, @Input(name = "mailbox", type = OoaType.STRING, required = false, description = D_MAILBOX) String mailbox) {
        Object f = outlook(ctx, foldersScript(mailbox), 300).getOrDefault("folders", List.of());
        return Map.of("folders", f instanceof List<?> l ? new ArrayList<>(l) : List.of(f));
    }
}
