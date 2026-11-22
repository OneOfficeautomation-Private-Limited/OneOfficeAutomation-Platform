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
@OoaPackage(id = "ooa.outlook_desktop", name = "Outlook desktop", version = "1.0.0",
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
        function Save-Mail($m, $dir, $format, $overwrite, $name) {
          $ext = @{ msg = '.msg'; txt = '.txt'; html = '.html'; mht = '.mht' }[[string]$format]
          $type = @{ msg = 3; txt = 0; html = 5; mht = 10 }[[string]$format]
          if (-not $name) {
            $name = ([string]$m.Subject) -replace '[\\\\/:*?"<>|\\r\\n\\t]', '_'
            if (-not $name.Trim()) { $name = 'email' }
            if ($name.Length -gt 120) { $name = $name.Substring(0, 120) }
            $name = $name.Trim() + $ext
          }
          New-Item -ItemType Directory -Force -Path $dir | Out-Null
          $target = Join-Path $dir $name
          if (-not $overwrite) {
            $n = 1
            while (Test-Path -LiteralPath $target) { $target = Join-Path $dir ([IO.Path]::GetFileNameWithoutExtension($name) + " ($n)" + [IO.Path]::GetExtension($name)); $n++ }
          }
          $m.SaveAs($target, $type)
          return $target
        }
        function Pick-Mails($in) {
          $f = Get-Folder $in.folder $in.mailbox
          $items = $f.Items
          if ($in.filter) { $items = $items.Restrict($in.filter) }
          $items.Sort('[ReceivedTime]', $true)
          $out = New-Object System.Collections.ArrayList
          for ($i = 1; $i -le $items.Count -and $out.Count -lt $in.max; $i++) {
            $m = $items.Item($i)
            if ($m.MessageClass -notlike 'IPM.Note*') { continue }
            if ($in.from -and -not ((Sender-Address $m) -like ('*' + $in.from + '*') -or ([string]$m.SenderName) -like ('*' + $in.from + '*'))) { continue }
            [void]$out.Add($m)
          }
          return ,$out
        }
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
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), "Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it.", true);
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
                default -> new ActionException("OUTLOOK.FAILED", "Outlook reported an error: " + ApplicationPackage.tail(err), "Check that Outlook is installed, set up for the runner's account and not showing a dialog.");
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
    static String filter(boolean unreadOnly, String subject, Object after) { return filter(unreadOnly, subject, after, null, false); }

    /** The same, also received before a date and only emails with attachments (G3). */
    static String filter(boolean unreadOnly, String subject, Object after, Object before, boolean withAttachments) {
        List<String> f = new ArrayList<>();
        if (unreadOnly) f.add("\"urn:schemas:httpmail:read\" = 0");
        if (subject != null && !subject.isBlank()) f.add("\"urn:schemas:httpmail:subject\" LIKE '%" + subject.strip().replace("'", "''").replace("%", "") + "%'");
        if (after != null && !Values.text(after).isBlank()) f.add("\"urn:schemas:httpmail:datereceived\" >= '" + utc(after) + "'");
        if (before != null && !Values.text(before).isBlank()) f.add("\"urn:schemas:httpmail:datereceived\" < '" + utc(before) + "'");
        if (withAttachments) f.add("\"urn:schemas:httpmail:hasattachment\" = 1");
        return f.isEmpty() ? "" : "@SQL=" + String.join(" AND ", f);
    }

    private static String utc(Object when) {
        java.time.Instant t = Values.toDateTime(when).atZone(java.time.ZoneId.systemDefault()).toInstant();
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(java.time.ZoneOffset.UTC).format(t);
    }

    /** G3 (AA parity): the mailbox named once by Use mailbox, for the steps after it that leave theirs empty. */
    static final class MailboxSession {
        final String mailbox;
        MailboxSession(String mailbox) { this.mailbox = mailbox; }
    }

    static final String SESSION = "outlook";

    /** The step's mailbox, or the one Use mailbox named, or the user's own (null). */
    static String mailbox(ActionContext ctx, String given) {
        if (given != null && !given.isBlank()) return given.strip();
        return ctx.sessions().get(SESSION) instanceof MailboxSession m ? m.mailbox : null;
    }

    private static final String D_MAILBOX = "Another mailbox open in the runner's Outlook profile (its name or address), e.g. a shared mailbox; empty for the user's own.";
    private static final String D_ID = "The email's id from Find emails.";
    private static final String D_BEFORE = "Only emails received before this date and time (G3).";
    private static final String D_WITH_ATT = "Only emails that have attachments (G3).";
    private static final String D_FORMAT = "File type: msg (Outlook, keeps attachments), txt, html or mht.";
    private static final String D_OVERWRITE = "Replace a file of the same name; off: the new file gets a number such as (1).";

    // ------------------------------------------------------------------------------------------------ G3: mailbox once

    @Action(id = "connect", name = "Use mailbox", category = "Mailbox", capabilities = "desktop",
            description = "Name the mailbox once, e.g. a shared mailbox, for every later Outlook desktop step that leaves its mailbox empty; it is checked now.",
            panel = "mailbox | share")
    public Map<String, Object> connect(ActionContext ctx,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, description = "A mailbox open in the runner's Outlook profile (its name or address); empty for the user's own.") String mailbox,
            @Input(name = "share", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Agents this one runs use the same mailbox.") boolean share) {
        String m = mailbox == null || mailbox.isBlank() ? null : mailbox.strip();
        outlook(ctx, new Script("  $null = Get-Root $in.mailbox\n  Out-Json @{ ok = $true }", in("mailbox", text(m))), 120);
        ctx.sessions().put(SESSION, new MailboxSession(m));
        if (share) ctx.shareSession(SESSION);
        return Map.of();
    }

    @Action(id = "disconnect", name = "Stop using mailbox", category = "Mailbox",
            description = "Later Outlook desktop steps go back to the user's own mailbox, or the one each names.")
    public Map<String, Object> disconnect(ActionContext ctx) {
        ctx.sessions().remove(SESSION);
        return Map.of();
    }

    @Condition(id = "folder_exists", name = "Outlook folder exists", description = "True when the mailbox has the folder, e.g. Inbox/Invoices.")
    public boolean folderExists(ActionContext ctx,
            @Input(name = "folder", type = OoaType.STRING, example = "Inbox/Invoices", description = "Folder path.") String folder,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, description = D_MAILBOX) String mailbox) {
        return Boolean.TRUE.equals(outlook(ctx, folderExistsScript(folder, mailbox(ctx, mailbox)), 120).get("exists"));
    }

    static Script folderExistsScript(String folder, String mailbox) {
        return new Script("""
              try { $null = Get-Folder $in.folder $in.mailbox; Out-Json @{ exists = $true } }
              catch { if ($_.Exception.Message -like 'OOA:FOLDER_NOT_FOUND*') { Out-Json @{ exists = $false } } else { throw } }
            """, in("folder", text(folder), "mailbox", text(mailbox)));
    }

    // ------------------------------------------------------------------------------------------------ G3: many emails at once

    /** Delete, move or save every email the filters find (at most max); the emails are picked first, then handled. */
    static Script bulkScript(String op, String folder, String mailbox, String filter, String from, int max,
                             String target, String targetMailbox, String dir, String format, boolean overwrite) {
        return new Script("""
              $pick = Pick-Mails $in
              $to = $null
              if ($in.op -eq 'move') { $to = Get-Folder $in.target $in.target_mailbox }
              $done = New-Object System.Collections.ArrayList
              foreach ($m in $pick) {
                if ($in.op -eq 'delete') { $id = [string]$m.EntryID; $m.Delete(); [void]$done.Add($id) }
                elseif ($in.op -eq 'move') { $x = $m.Move($to); [void]$done.Add([string]$x.EntryID) }
                else { [void]$done.Add((Save-Mail $m $in.dir $in.format $in.overwrite $null)) }
              }
              Out-Json @{ done = @($done) }
            """, in("op", op, "folder", text(folder), "mailbox", text(mailbox), "filter", filter, "from", text(from), "max", max,
                "target", text(target), "target_mailbox", text(targetMailbox), "dir", text(dir), "format", format(format), "overwrite", overwrite));
    }

    private static String format(String f) {
        String x = f == null || f.isBlank() ? "msg" : f.strip().toLowerCase(Locale.ROOT);
        if (!List.of("msg", "txt", "html", "mht").contains(x)) throw new ActionException("OUTLOOK.BAD_FORMAT", "Unknown file type " + f, "Use msg, txt, html or mht.");
        return x;
    }

    private static List<Object> done(Map<String, Object> r) {
        Object d = r.getOrDefault("done", List.of());
        return d instanceof List<?> l ? new ArrayList<>(l) : new ArrayList<>(List.of(d));
    }

    @Action(id = "delete_all", name = "Delete all emails", category = "Mailbox", timeoutSeconds = 600, capabilities = "desktop",
            description = "Delete every email in a folder that the filters find (Outlook moves them to Deleted Items).",
            panel = "folder, unread_only, subject_contains, from_contains, received_after, received_before, with_attachments_only, max | mailbox")
    @Output(name = "count", type = OoaType.NUMBER, description = "How many emails were deleted.")
    public Map<String, Object> deleteAll(ActionContext ctx,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"Inbox\"", example = "Inbox/Old", description = "Folder path.") String folder,
            @Input(name = "unread_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only unread emails.") boolean unreadOnly,
            @Input(name = "subject_contains", type = OoaType.STRING, required = false, description = "Only emails whose subject contains this text.") String subject,
            @Input(name = "from_contains", type = OoaType.STRING, required = false, description = "Only emails whose sender address or name contains this text.") String from,
            @Input(name = "received_after", type = OoaType.DATETIME, required = false, description = "Only emails received after this date and time.") Object after,
            @Input(name = "received_before", type = OoaType.DATETIME, required = false, description = D_BEFORE) Object before,
            @Input(name = "with_attachments_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = D_WITH_ATT) boolean withAtt,
            @Input(name = "max", type = OoaType.NUMBER, required = false, defaultValue = "500", min = 1, max = 5000, description = "At most this many emails.") int max,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, description = D_MAILBOX) String mailbox) {
        List<Object> d = done(outlook(ctx, bulkScript("delete", folder, mailbox(ctx, mailbox), filter(unreadOnly, subject, after, before, withAtt), from, max, null, null, null, null, false), 600));
        return Map.of("count", BigDecimal.valueOf(d.size()));
    }

    @Action(id = "move_all", name = "Move all emails", category = "Mailbox", timeoutSeconds = 600, capabilities = "desktop",
            description = "Move every email in a folder that the filters find to another folder, e.g. Inbox/Done. The emails get new ids.",
            panel = "folder, to_folder, unread_only, subject_contains, from_contains, received_after, received_before, with_attachments_only, max | mailbox, to_mailbox")
    @Output(name = "ids", type = OoaType.LIST, description = "The moved emails' new ids.")
    @Output(name = "count", type = OoaType.NUMBER, description = "How many emails were moved.")
    public Map<String, Object> moveAll(ActionContext ctx,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"Inbox\"", description = "Folder path to move from.") String folder,
            @Input(name = "to_folder", type = OoaType.STRING, example = "Inbox/Done", description = "Folder path to move them to.") String toFolder,
            @Input(name = "unread_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only unread emails.") boolean unreadOnly,
            @Input(name = "subject_contains", type = OoaType.STRING, required = false, description = "Only emails whose subject contains this text.") String subject,
            @Input(name = "from_contains", type = OoaType.STRING, required = false, description = "Only emails whose sender address or name contains this text.") String from,
            @Input(name = "received_after", type = OoaType.DATETIME, required = false, description = "Only emails received after this date and time.") Object after,
            @Input(name = "received_before", type = OoaType.DATETIME, required = false, description = D_BEFORE) Object before,
            @Input(name = "with_attachments_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = D_WITH_ATT) boolean withAtt,
            @Input(name = "max", type = OoaType.NUMBER, required = false, defaultValue = "500", min = 1, max = 5000, description = "At most this many emails.") int max,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, description = D_MAILBOX) String mailbox,
            @Input(name = "to_mailbox", type = OoaType.STRING, required = false, description = "The mailbox of the folder to move to; the same mailbox when empty.") String toMailbox) {
        String mb = mailbox(ctx, mailbox);
        List<Object> d = done(outlook(ctx, bulkScript("move", folder, mb, filter(unreadOnly, subject, after, before, withAtt), from, max, toFolder,
                toMailbox == null || toMailbox.isBlank() ? mb : toMailbox.strip(), null, null, false), 600));
        return Map.of("ids", d, "count", BigDecimal.valueOf(d.size()));
    }

    @Action(id = "save_all", name = "Save all emails", category = "Mailbox", timeoutSeconds = 600, capabilities = {"desktop", "filesystem"},
            description = "Save every email in a folder that the filters find as files (.msg keeps the attachments), named after their subjects.",
            panel = "folder, save_to, format, overwrite, unread_only, subject_contains, from_contains, received_after, received_before, with_attachments_only, max | mailbox")
    @Output(name = "files", type = OoaType.LIST, description = "The saved files.")
    public Map<String, Object> saveAll(ActionContext ctx,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"Inbox\"", description = "Folder path.") String folder,
            @Input(name = "save_to", type = OoaType.FILE, description = "Folder on the runner to save into.") String saveTo,
            @Input(name = "format", type = OoaType.STRING, required = false, defaultValue = "\"msg\"", enumValues = {"msg", "txt", "html", "mht"}, description = D_FORMAT) String format,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = D_OVERWRITE) boolean overwrite,
            @Input(name = "unread_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only unread emails.") boolean unreadOnly,
            @Input(name = "subject_contains", type = OoaType.STRING, required = false, description = "Only emails whose subject contains this text.") String subject,
            @Input(name = "from_contains", type = OoaType.STRING, required = false, description = "Only emails whose sender address or name contains this text.") String from,
            @Input(name = "received_after", type = OoaType.DATETIME, required = false, description = "Only emails received after this date and time.") Object after,
            @Input(name = "received_before", type = OoaType.DATETIME, required = false, description = D_BEFORE) Object before,
            @Input(name = "with_attachments_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = D_WITH_ATT) boolean withAtt,
            @Input(name = "max", type = OoaType.NUMBER, required = false, defaultValue = "500", min = 1, max = 5000, description = "At most this many emails.") int max,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, description = D_MAILBOX) String mailbox) {
        String dir = ctx.resolvePath(saveTo).toAbsolutePath().toString();
        return Map.of("files", done(outlook(ctx, bulkScript("save", folder, mailbox(ctx, mailbox), filter(unreadOnly, subject, after, before, withAtt), from, max,
                null, null, dir, format, overwrite), 600)));
    }

    static Script saveEmailScript(String id, String dir, String name, String format, boolean overwrite) {
        return new Script("""
              $m = Get-Mail $in.id
              $p = Save-Mail $m $in.dir $in.format $in.overwrite $in.name
              Out-Json @{ path = $p }
            """, in("id", id, "dir", dir, "name", name == null || name.isBlank() ? null : name.strip(), "format", format(format), "overwrite", overwrite));
    }

    @Action(id = "save_email", name = "Save email", category = "Mailbox", capabilities = {"desktop", "filesystem"},
            description = "Save one email as a file: .msg (opens in Outlook, keeps the attachments), text, HTML or MHT.",
            panel = "id, save_to, format | file_name, overwrite")
    @Output(name = "path", type = OoaType.FILE, description = "The saved file.")
    public Map<String, Object> saveEmail(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, description = D_ID) String id,
            @Input(name = "save_to", type = OoaType.FILE, description = "Folder on the runner to save into.") String saveTo,
            @Input(name = "format", type = OoaType.STRING, required = false, defaultValue = "\"msg\"", enumValues = {"msg", "txt", "html", "mht"}, description = D_FORMAT) String format,
            @Input(name = "file_name", type = OoaType.STRING, required = false, description = "File name with its extension; the email's subject when empty.") String fileName,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = D_OVERWRITE) boolean overwrite) {
        if (fileName != null && fileName.matches(".*[\\\\/:*?\"<>|].*")) throw new ActionException("OUTLOOK.BAD_NAME", "The file name " + fileName + " has a character files cannot have", "Leave out \\ / : * ? \" < > |.");
        String dir = ctx.resolvePath(saveTo).toAbsolutePath().toString();
        return Map.of("path", Values.text(outlook(ctx, saveEmailScript(id, dir, fileName, format, overwrite), 120).getOrDefault("path", "")));
    }

    // ------------------------------------------------------------------------------------------------

    static Script listScript(String folder, String mailbox, boolean unreadOnly, String subject, String from, Object after, int max) {
        return listScript(folder, mailbox, filter(unreadOnly, subject, after), from, max);
    }

    static Script listScript(String folder, String mailbox, String filter, String from, int max) {
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
        return new Script(body, in("folder", text(folder), "mailbox", text(mailbox), "filter", filter, "from", text(from), "max", max));
    }

    public Map<String, Object> list(ActionContext ctx, String folder, boolean unreadOnly, String subject, String from, Object after, int max, String mailbox, int timeout) {
        return list(ctx, folder, unreadOnly, subject, from, after, max, mailbox, timeout, null, false);
    }

    @Action(id = "list", name = "Find emails", category = "Mailbox", idempotent = true, timeoutSeconds = 300, capabilities = "desktop",
            description = "List the newest emails in an Outlook folder, optionally only unread ones, by subject, by sender or received after a date.",
            panel = "folder, unread_only, subject_contains, from_contains, received_after, received_before, with_attachments_only, max | mailbox, timeout_seconds")
    @Output(name = "emails", type = OoaType.LIST, description = "Emails, newest first: id, subject, from, from_name, to, received, unread, attachments, folder.")
    @Output(name = "count", type = OoaType.NUMBER, description = "How many were found.")
    @Output(name = "table", type = OoaType.TABLE, description = "The same emails as a table, as the other mail services give it: id, from, subject, received, unread, has_attachments.")
    public Map<String, Object> list(ActionContext ctx,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"Inbox\"", example = "Inbox/Invoices", description = "Folder path: Inbox, Sent Items, Drafts, or a folder below them, e.g. Inbox/Invoices.") String folder,
            @Input(name = "unread_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only unread emails.") boolean unreadOnly,
            @Input(name = "subject_contains", type = OoaType.STRING, required = false, description = "Only emails whose subject contains this text.") String subject,
            @Input(name = "from_contains", type = OoaType.STRING, required = false, description = "Only emails whose sender address or name contains this text.") String from,
            @Input(name = "received_after", type = OoaType.DATETIME, required = false, description = "Only emails received after this date and time.") Object after,
            @Input(name = "max", type = OoaType.NUMBER, required = false, defaultValue = "50", min = 1, max = 5000, description = "At most this many emails.") int max,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, description = D_MAILBOX) String mailbox,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "120", description = "Maximum time.") int timeout,
            @Input(name = "received_before", type = OoaType.DATETIME, required = false, description = D_BEFORE) Object before,
            @Input(name = "with_attachments_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = D_WITH_ATT) boolean withAtt) {
        Object emails = outlook(ctx, listScript(folder, mailbox(ctx, mailbox), filter(unreadOnly, subject, after, before, withAtt), from, max), timeout).getOrDefault("emails", List.of());
        List<Object> l = emails instanceof List<?> x ? new ArrayList<>(x) : List.of(emails);
        // A2: the same table as Find emails of the other mail services, next to the list agents written before use
        List<Object> rows = new ArrayList<>();
        for (Object o : l) if (o instanceof Map<?, ?> m) {
            boolean atts = m.get("attachments") instanceof Number n ? n.intValue() > 0 : Boolean.TRUE.equals(m.get("attachments"));
            rows.add(new ArrayList<>(Arrays.asList(m.get("id"), m.get("from"), m.get("subject"), m.get("received"), m.get("unread"), atts)));
        }
        return Map.of("emails", l, "count", BigDecimal.valueOf(l.size()),
                "table", com.oneofficeautomation.runner.Values.table(List.of("id", "from", "subject", "received", "unread", "has_attachments"), rows));
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
    @Output(name = "email", type = OoaType.DICTIONARY, description = "id, subject, from, from_name, to, cc, received, unread, body, html_body, categories, attachment_names, folder. Also, the same on every mail service: id, from, to, cc, subject, received, text, html, attachments (names), has_attachments, unread, message_id.")
    public Map<String, Object> get(ActionContext ctx, @Input(name = "id", type = OoaType.STRING, description = D_ID) String id) {
        return Map.of("email", read(ctx, id));
    }

    @LoopIterator(id = "emails", name = "Each email in an Outlook folder",
            description = "Go through the emails matching the Find emails filters, one per turn: id, subject, from, from_name, to, received, unread, attachments, folder, or the whole email.")
    public Iterable<Object> emails(ActionContext ctx,
            @Input(name = "folder", type = OoaType.STRING, required = false, defaultValue = "\"Inbox\"", example = "Inbox/Invoices", description = "Folder path: Inbox, Sent Items, Drafts, or a folder below them, e.g. Inbox/Invoices.") String folder,
            @Input(name = "unread_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only unread emails.") boolean unreadOnly,
            @Input(name = "subject_contains", type = OoaType.STRING, required = false, description = "Only emails whose subject contains this text.") String subject,
            @Input(name = "from_contains", type = OoaType.STRING, required = false, description = "Only emails whose sender address or name contains this text.") String from,
            @Input(name = "received_after", type = OoaType.DATETIME, required = false, description = "Only emails received after this date and time.") Object after,
            @Input(name = "max", type = OoaType.NUMBER, required = false, defaultValue = "50", min = 1, max = 5000, description = "At most this many emails.") int max,
            @Input(name = "with_body", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = MailRecord.D_WITH_BODY) boolean withBody,
            @Input(name = "mailbox", type = OoaType.STRING, required = false, description = D_MAILBOX) String mailbox,
            @Input(name = "received_before", type = OoaType.DATETIME, required = false, description = D_BEFORE) Object before,
            @Input(name = "with_attachments_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = D_WITH_ATT) boolean withAtt,
            @Input(name = "read_state", type = OoaType.STRING, required = false, defaultValue = "\"all\"", enumValues = {"all", "unread", "read"},
                    description = "All emails, only unread ones or only read ones (G3); unread_only still works.") String readState) {
        List<Map<String, Object>> found = new ArrayList<>();
        boolean onlyRead = "read".equalsIgnoreCase(readState == null ? "" : readState.strip());
        boolean unread = unreadOnly || "unread".equalsIgnoreCase(readState == null ? "" : readState.strip());
        Object emails = list(ctx, folder, unread, subject, from, after, max, mailbox, 120, before, withAtt).get("emails");
        if (emails instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> m) {
            if (onlyRead && Boolean.TRUE.equals(m.get("unread"))) continue;
            @SuppressWarnings("unchecked") Map<String, Object> row = new LinkedHashMap<>((Map<String, Object>) m);
            row.put("has_attachments", m.get("attachments") instanceof Number n && n.intValue() > 0);
            found.add(row);
        }
        return MailRecord.each(found, withBody, row -> read(ctx, String.valueOf(row.get("id"))));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(ActionContext ctx, String id) {
        Object raw = outlook(ctx, getScript(id), 120);
        @SuppressWarnings("unchecked") Map<String, Object> e = raw instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
        return MailRecord.withCommonKeys(e);
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
        if (to == null || to.isBlank()) throw new ActionException("OUTLOOK.NO_RECIPIENT", "Give at least one recipient", "Add at least one recipient.");
        Map<String, Object> r = outlook(ctx, sendScript(to, cc, bcc, subject, body, html, files(ctx, attachments), account, draft), 120);
        return Map.of("draft_id", Values.text(r.getOrDefault("id", "")));
    }

    static Script replyScript(String id, String body, boolean all, String forwardTo, boolean draft) {
        return replyScript(id, body, all, forwardTo, draft, null, null, List.of(), false);
    }

    /** Reply or forward; G3 adds copies, blind copies, files to attach and an HTML note. */
    static Script replyScript(String id, String body, boolean all, String forwardTo, boolean draft, String cc, String bcc, List<String> attachments, boolean html) {
        return new Script("""
              $m = Get-Mail $in.id
              if ($in.forward_to) { $r = $m.Forward(); $r.To = $in.forward_to } elseif ($in.all) { $r = $m.ReplyAll() } else { $r = $m.Reply() }
              if ($in.cc) { $r.CC = (@([string]$r.CC, $in.cc) | Where-Object { $_ }) -join '; ' }
              if ($in.bcc) { $r.BCC = $in.bcc }
              foreach ($a in @($in.attachments)) { if ($a) { if (-not (Test-Path -LiteralPath $a)) { throw "OOA:NO_FILE:$a" }; [void]$r.Attachments.Add($a) } }
              if ($in.body) { $note = $(if ($in.html) { [string]$in.body } else { '<p>' + (Html-Text $in.body) + '</p>' }); $r.HTMLBody = $note + $r.HTMLBody }
              if ($in.draft) { $r.Save(); Out-Json @{ id = $r.EntryID } } else { $r.Send(); Out-Json @{ id = '' } }
            """, in("id", id, "body", text(body), "all", all, "forward_to", forwardTo, "draft", draft, "cc", text(cc), "bcc", text(bcc),
                "attachments", attachments == null ? List.of() : attachments, "html", html));
    }

    public Map<String, Object> reply(ActionContext ctx, String id, String body, boolean all, boolean draft) {
        return reply(ctx, id, body, all, draft, null, null, null, false);
    }

    @Action(id = "reply", name = "Reply to email", category = "Send", timeoutSeconds = 120, capabilities = "desktop",
            description = "Reply to the sender (or everyone) above the original message, with copies and files if wanted; send it or keep it in Drafts.",
            panel = "id, body, html, reply_all, attachments | cc, bcc, save_as_draft")
    @Output(name = "draft_id", type = OoaType.STRING, description = "The draft's id when it was kept as a draft, else empty.")
    public Map<String, Object> reply(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, description = D_ID) String id,
            @Input(name = "body", type = OoaType.STRING, required = false, description = "Your reply (text).") String body,
            @Input(name = "reply_all", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Reply to everyone on the email.") boolean all,
            @Input(name = "save_as_draft", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Keep it in Drafts instead of sending.") boolean draft,
            @Input(name = "cc", type = OoaType.STRING, required = false, description = "Also copy to (G3), separated by semicolons.") String cc,
            @Input(name = "bcc", type = OoaType.STRING, required = false, description = "Blind copy to (G3).") String bcc,
            @Input(name = "attachments", type = OoaType.LIST, required = false, description = "Files to attach (G3).") List<Object> attachments,
            @Input(name = "html", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "The reply is HTML (bold, links, lists) (G3).") boolean html) {
        return Map.of("draft_id", Values.text(outlook(ctx, replyScript(id, body, all, null, draft, cc, bcc, files(ctx, attachments), html), 120).getOrDefault("id", "")));
    }

    public Map<String, Object> forward(ActionContext ctx, String id, String to, String body, boolean draft) {
        return forward(ctx, id, to, body, draft, null, null, null, false);
    }

    @Action(id = "forward", name = "Forward email", category = "Send", timeoutSeconds = 120, capabilities = "desktop",
            description = "Forward an email with its attachments, with a note above it and more files if wanted; send it or keep it in Drafts.",
            panel = "id, to, body, html, attachments | cc, bcc, save_as_draft")
    @Output(name = "draft_id", type = OoaType.STRING, description = "The draft's id when it was kept as a draft, else empty.")
    public Map<String, Object> forward(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, description = D_ID) String id,
            @Input(name = "to", type = OoaType.STRING, description = "Recipients, separated by semicolons.") String to,
            @Input(name = "body", type = OoaType.STRING, required = false, description = "A note above the forwarded email.") String body,
            @Input(name = "save_as_draft", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Keep it in Drafts instead of sending.") boolean draft,
            @Input(name = "cc", type = OoaType.STRING, required = false, description = "Copy to (G3), separated by semicolons.") String cc,
            @Input(name = "bcc", type = OoaType.STRING, required = false, description = "Blind copy to (G3).") String bcc,
            @Input(name = "attachments", type = OoaType.LIST, required = false, description = "More files to attach (G3).") List<Object> attachments,
            @Input(name = "html", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "The note is HTML (bold, links, lists) (G3).") boolean html) {
        if (to == null || to.isBlank()) throw new ActionException("OUTLOOK.NO_RECIPIENT", "Give at least one recipient", "Add at least one recipient.");
        return Map.of("draft_id", Values.text(outlook(ctx, replyScript(id, body, false, to, draft, cc, bcc, files(ctx, attachments), html), 120).getOrDefault("id", "")));
    }

    static Script saveAttachmentsScript(String id, String folder, String pattern) { return saveAttachmentsScript(id, folder, pattern, false); }

    static Script saveAttachmentsScript(String id, String folder, String pattern, boolean overwrite) {
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
                while (-not $in.overwrite -and (Test-Path -LiteralPath $target)) { $target = Join-Path $in.folder ([IO.Path]::GetFileNameWithoutExtension($name) + " ($n)" + [IO.Path]::GetExtension($name)); $n++ }
                $a.SaveAsFile($target)
                [void]$saved.Add($target)
              }
              Out-Json @{ files = @($saved) }
            """, in("id", id, "folder", folder, "pattern", text(pattern), "overwrite", overwrite));
    }

    public Map<String, Object> saveAttachments(ActionContext ctx, String id, String folder, String pattern) { return saveAttachments(ctx, id, folder, pattern, false); }

    @Action(id = "save_attachments", name = "Save attachments", category = "Mailbox", capabilities = {"desktop", "filesystem"},
            description = "Save an email's attachments to a folder, optionally only those matching a name pattern; existing files are kept and new ones numbered, or replaced.",
            panel = "id, folder, name_pattern | overwrite")
    @Output(name = "files", type = OoaType.LIST, description = "The saved files.")
    public Map<String, Object> saveAttachments(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, description = D_ID) String id,
            @Input(name = "folder", type = OoaType.FILE, description = "Folder to save into.") String folder,
            @Input(name = "name_pattern", type = OoaType.STRING, required = false, example = "*.pdf", description = "Only attachments whose name matches, e.g. *.pdf.") String pattern,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = D_OVERWRITE + " (G3)") boolean overwrite) {
        Object files = outlook(ctx, saveAttachmentsScript(id, ctx.resolvePath(folder).toAbsolutePath().toString(), pattern, overwrite), 300).getOrDefault("files", List.of());
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
        return Map.of("id", Values.text(outlook(ctx, moveScript(id, folder, mailbox(ctx, mailbox)), 120).getOrDefault("id", "")));
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
        Object f = outlook(ctx, foldersScript(mailbox(ctx, mailbox)), 300).getOrDefault("folders", List.of());
        return Map.of("folders", f instanceof List<?> l ? new ArrayList<>(l) : List.of(f));
    }
}
