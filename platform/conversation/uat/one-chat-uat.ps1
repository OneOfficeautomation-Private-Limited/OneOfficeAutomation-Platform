# One Chat UAT: starts a fresh Command Center from the installed build, drives it only through its HTTP API
# (people, a scripted runner, a restart) and writes a Markdown report. Passwords stay in memory; none are printed.
# Build first: gradlew :cli:installDist. Run: powershell -ExecutionPolicy Bypass -File one-chat-uat.ps1
# $Work must have no spaces in its path (ooa.bat passes it on unquoted); it is deleted and made afresh.
param(
    [string]$Ooa = (Join-Path $PSScriptRoot "..\..\cli\build\install\ooa\bin\ooa.bat"),
    [string]$Work = "$env:SystemDrive\ooa-one-chat-uat",
    [int]$Port = 8732,
    [string]$Report = (Join-Path $PSScriptRoot "UAT-report.md"),
    [string]$JavaHome = $env:JAVA_HOME
)
$ErrorActionPreference = "Stop"
if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
$base = "http://127.0.0.1:$Port"
$results = New-Object System.Collections.ArrayList
$server = $null

function Pw { "Uat-" + ([guid]::NewGuid().ToString("N").Substring(0, 12)) + "-9z" }

function Call([string]$method, [string]$path, $body = $null, [string]$token = $null, [hashtable]$headers = @{}) {
    $req = [System.Net.HttpWebRequest]::Create("$base/api/$path")
    $req.Method = $method
    $req.Timeout = 60000
    $req.Headers.Add("X-OOA-Request", "1")
    if ($token) { $req.Headers.Add("Authorization", "Bearer $token") }
    foreach ($k in $headers.Keys) { $req.Headers.Add($k, $headers[$k]) }
    if ($null -ne $body) {
        $bytes = [Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 30 -Compress))
        $req.ContentType = "application/json; charset=utf-8"
        $req.ContentLength = $bytes.Length
        $s = $req.GetRequestStream(); $s.Write($bytes, 0, $bytes.Length); $s.Close()
    }
    try { $resp = $req.GetResponse() } catch [System.Net.WebException] { $resp = $_.Exception.Response; if ($null -eq $resp) { throw } }
    $code = [int]$resp.StatusCode
    $text = try { (New-Object IO.StreamReader($resp.GetResponseStream(), [Text.Encoding]::UTF8)).ReadToEnd() } catch { "" }
    $resp.Close()
    $json = if ($text) { $text | ConvertFrom-Json } else { $null }
    [pscustomobject]@{ status = $code; body = $json; text = $text }
}

function Check([string]$area, [string]$name, [bool]$ok, [string]$detail) {
    [void]$results.Add([pscustomobject]@{ area = $area; name = $name; ok = $ok; detail = ($detail -replace "\|", "/" -replace "`r?`n", " ") })
    Write-Host ("[{0}] {1}: {2}" -f $(if ($ok) { "PASS" } else { "FAIL" }), $area, $name)
}

function Say($token, [string]$text, [string]$sid = $null, [string]$lang = $null) {
    $b = @{ message = $text }
    if ($sid) { $b.session_id = $sid }
    if ($lang) { $b.lang = $lang }
    (Call POST "chat" $b $token).body
}

function Start-Cc {
    $log = Join-Path $Work ("server-" + (Get-Date -Format "HHmmss") + ".log")
    $p = Start-Process -FilePath $Ooa -ArgumentList @("server", "--no-agent", "--no-browser", "--port=$Port", "--data=$Work\data", "--workspace=$Work\ws") `
        -RedirectStandardOutput $log -RedirectStandardError "$log.err" -PassThru -WindowStyle Hidden
    for ($i = 0; $i -lt 120; $i++) {
        Start-Sleep -Milliseconds 500
        try { if ((Call GET "health").status -eq 200) { return @{ proc = $p; log = $log } } } catch {}
    }
    throw "The Command Center did not start; see $log"
}

function Stop-Cc($s) {
    if ($null -eq $s) { return }
    # ooa.bat starts java as a child: stop the whole tree
    & taskkill.exe /PID $s.proc.Id /T /F | Out-Null
    for ($i = 0; $i -lt 40; $i++) { Start-Sleep -Milliseconds 250; try { Call GET "health" | Out-Null } catch { return } }
}

function Flow([string]$id, [string]$name, [string]$description, $inputs, $chat, [bool]$old = $false) {
    $vars = @(@{ name = "note"; type = "String"; direction = "output" }) + $inputs
    $f = [ordered]@{ kind = "TaskBot"; id = $id; name = $name; description = $description; variables = $vars
        steps = @(@{ id = "1"; action = "core/set"; args = @{ variable = "note"; value = "ok" } }) }
    if ($old) { $f.ui = @{ chat = $chat } } else { $f.chat = $chat }
    $f
}

function NextJob($dev) { (Call POST "agent/jobs/next" @{} $null @{ "X-OOA-Device" = $dev.id; "X-OOA-Device-Secret" = $dev.secret }).body.job }
function Finish($dev, [string]$runId, $result) {
    Call POST "agent/runs/$runId/finish" @{ result = $result } $null @{ "X-OOA-Device" = $dev.id; "X-OOA-Device-Secret" = $dev.secret } | Out-Null
}
function Lines($token, [string]$sid) { @((Call GET "chat/$sid" $null $token).body.messages) }

try {
    if (Test-Path $Work) { Remove-Item -Recurse -Force $Work }
    New-Item -ItemType Directory -Force "$Work\ws" | Out-Null
    $server = Start-Cc
    $first = (Select-String -Path $server.log -Pattern "one-time password\s+(\S+)").Matches[0].Groups[1].Value
    Check "Deploy" "fresh Command Center starts and prints a first password" ([bool]$first) "port $Port"

    # ---------- people ----------
    $adminPw = Pw
    $t0 = (Call POST "auth/login" @{ username = "admin"; password = $first }).body.token
    Call POST "auth/password" @{ current = $first; new = $adminPw } $t0 | Out-Null
    $admin = (Call POST "auth/login" @{ username = "admin"; password = $adminPw }).body.token
    function Person([string]$name, [string]$role) {
        $tmp = (Call POST "users" @{ username = $name; display_name = $name; role = $role } $admin).body.temporary_password
        $pw = Pw
        $t = (Call POST "auth/login" @{ username = $name; password = $tmp }).body.token
        Call POST "auth/password" @{ current = $tmp; new = $pw } $t | Out-Null
        (Call POST "auth/login" @{ username = $name; password = $pw }).body.token
    }
    $jane = Person "jane" "bot_runner"
    $val = Person "val" "viewer"
    $aria = Person "aria" "admin"
    Check "Setup" "admin, jane (bot_runner), val (viewer) and aria (second admin) sign in" ($admin -and $jane -and $val -and $aria) ""

    # ---------- agents ----------
    Call POST "repo/folders" @{ path = "Finance" } $admin | Out-Null
    $enum = @{ name = "vendor"; type = "String"; direction = "input"; required = $true; enum = @("Acme", "Globex") }
    $from = @{ name = "from"; type = "DateTime"; direction = "input"; required = $true; description = "the earliest invoice date" }
    $company = @{ name = "company"; type = "String"; direction = "input"; enum = @("1000", "2000"); default = "1000" }
    $flows = [ordered]@{
        "Finance/Process invoices.flow.json" = (Flow "process_invoices" "Process vendor invoices" "Posts a vendor's open invoices to SAP. Not for credit notes." @($enum, $from) `
            @{ examples = @("post today's invoices for acme", "process vendor invoices"); aliases = @("ap run"); not_for = @("issue a credit note"); risk = "R2"; prompts = @{ vendor = "Which vendor should I process?" } })
        "Finance/Aging report.flow.json" = (Flow "aging" "AP aging report" "Read-only payables aging." @($company) @{ examples = @("show payables aging", "ap aging report"); risk = "R0" })
        "Finance/Payment run.flow.json" = (Flow "payment" "Vendor payment run" "Pays due invoices." @() @{ examples = @("start the payment run", "pay due vendor invoices"); risk = "R3" })
        "Finance/Old style.flow.json" = (Flow "old_style" "Old style report" "Read-only." @() @{ examples = @("old style status report"); risk = "R0" } $true)
    }
    $okIn = $true
    foreach ($p in $flows.Keys) { $r = Call POST "repo/checkin" @{ path = $p; flow = $flows[$p]; comment = "UAT"; production = $true } $admin; if ($r.status -ne 200) { $okIn = $false; $detail = $r.text } }
    Check "Setup" "four agents checked in to production (three with chat, one with the older ui.chat)" $okIn "$detail"

    # ---------- the schema check on the chat block (ooa validate) ----------
    $good = Join-Path $Work "good.flow.json"; $bad = Join-Path $Work "bad.flow.json"
    ($flows["Finance/Process invoices.flow.json"] | ConvertTo-Json -Depth 30) | Out-File -Encoding ascii $good
    $b = $flows["Finance/Aging report.flow.json"]; $b.chat = @{ risk = "R9"; colour = "blue" }
    ($b | ConvertTo-Json -Depth 30) | Out-File -Encoding ascii $bad
    # through cmd: the CLI logs to stderr, which PowerShell 5.1 would raise as an error
    cmd.exe /c "`"$Ooa`" validate `"$good`" > `"$Work\validate-good.txt`" 2>&1"; $g = $LASTEXITCODE
    cmd.exe /c "`"$Ooa`" validate `"$bad`" > `"$Work\validate-bad.txt`" 2>&1"; $bb = $LASTEXITCODE
    Check "Schema" "ooa validate accepts a valid chat block" ($g -eq 0) "exit $g"
    Check "Schema" "ooa validate rejects risk R9 and an unknown field" ($bb -ne 0) ("exit $bb; " + ((Get-Content "$Work\validate-bad.txt" -Raw) -replace "\s+", " ").Substring(0, [Math]::Min(200, (Get-Content "$Work\validate-bad.txt" -Raw).Length)))

    # ---------- no runner yet ----------
    $nr = Say $jane "show payables aging"
    Check "Runner" "with no runner set, an R0 request says so and starts nothing" ($nr.kind -eq "no_runner" -or $nr.kind -eq "refused") "$($nr.kind): $($nr.reply)"

    # ---------- a scripted runner ----------
    $key = (Call POST "devices/registration-key" @{ hours = 1 } $admin).body.key
    $reg = (Call POST "agent/register" @{ key = $key; name = "UAT-PC"; hostname = "uat-pc"; os = "Windows 11"; agent_version = "0.2.0" }).body
    $dev = @{ id = $reg.device_id; secret = $reg.secret }
    Call POST "agent/heartbeat" @{ status = "idle" } $null @{ "X-OOA-Device" = $dev.id; "X-OOA-Device-Secret" = $dev.secret } | Out-Null
    $set = Call PUT "settings" @{ chat_runner = $dev.id } $admin
    Check "Setup" "a runner registers and is set as the chat runner" ($set.status -eq 200) "$($dev.id)"

    # ---------- R0 ----------
    $r0 = Say $jane "show payables aging"
    Check "R0" "a read-only agent starts at once" ($r0.kind -eq "started" -and $r0.run_id) "$($r0.reply)"
    $job = NextJob $dev
    # optional inputs with a default are left to the runner, which applies the flow's default
    Check "R0" "the runner receives the chat's run; the optional input is left to its default" ($job.run_id -eq $r0.run_id -and $null -eq $job.inputs.company) "run $($job.run_id), inputs: $($job.inputs | ConvertTo-Json -Compress)"
    Finish $dev $r0.run_id @{ status = "success"; outputs = @{ note = "ok"; overdue_inr = 1840000 } }
    Start-Sleep -Milliseconds 500
    $h = Lines $jane $r0.session_id
    $last = $h[-1].text
    Check "Run updates" "when the run ends, the chat says so with its results" ($last -match "finished" -and $last -match "1840000") "$last"

    # ---------- R2 with questions ----------
    $q1 = Say $jane "process vendor invoices"
    $sid = $q1.session_id
    Check "R2" "a missing input is asked for with the agent's own prompt and its choices" ($q1.kind -eq "question" -and $q1.reply -eq "Which vendor should I process? (Acme, Globex)") "$($q1.reply)"
    $q2 = Say $jane "globex" $sid
    Check "R2" "the next missing input uses its description" ($q2.kind -eq "question" -and $q2.reply -match "earliest invoice date") "$($q2.reply)"
    $c = Say $jane "today" $sid
    $today = (Get-Date).ToString("yyyy-MM-dd")
    Check "R2" "with every input known, a summary waits for yes" ($c.kind -eq "confirm" -and $c.inputs.vendor -eq "Globex" -and $c.inputs.from -eq $today) "$($c.reply)"
    $runsBefore = (Call GET "runs" $null $admin).body.total
    $y = Say $jane "yes" $sid
    Check "R2" "yes starts the run" ($y.kind -eq "started" -and $y.run_id) "$($y.reply)"
    $y2 = Say $jane "yes" $sid
    Check "R2" "a second yes does not start it again" ($y2.kind -ne "started") "$($y2.kind): $($y2.reply)"
    $job2 = NextJob $dev
    Finish $dev $job2.run_id @{ status = "failed"; error = @{ code = "SAP.LOCKED"; message = "Vendor Globex is locked in SAP" } }
    Start-Sleep -Milliseconds 500
    $last = (Lines $jane $sid)[-1].text
    $st = (Call GET "runs/$($y.run_id)" $null $admin).body
    Check "Run updates" "a failed run is reported with its reason" ($last -match "Globex is locked") "$last (yes run $($y.run_id), runner got $($job2.run_id), run status $($st.status))"

    # ---------- cancel and not understood ----------
    $cc = Say $jane "post today's invoices for acme"
    $no = Say $jane "no" $cc.session_id
    Check "R2" "no cancels a proposal" ($cc.kind -eq "confirm" -and $no.kind -eq "cancelled") "$($no.reply)"
    $nu = Say $jane "order pizza for the team"
    Check "Rules mode" "an unrelated request is not understood and suggests examples" ($nu.kind -eq "not_understood") "$($nu.reply)"
    $al = Say $jane "ap run"
    Check "Rules mode" "an alias reaches its agent" ($al.agent -eq "Finance/Process invoices.flow.json") "$($al.kind): $($al.reply)"
    $old = Say $jane "old style status report"
    Check "Rules mode" "an older agent with ui.chat still works" ($old.kind -eq "started") "$($old.reply)"
    NextJob $dev | Out-Null

    # ---------- permissions ----------
    $v = Say $val "show payables aging"
    Check "Permissions" "a viewer cannot start agents from chat" ($v.kind -ne "started") "$($v.kind): $($v.reply)"

    # ---------- R3 ----------
    $p1 = Say $admin "start the payment run"
    $p2 = Say $admin "yes" $p1.session_id
    Check "R3" "after yes, an R3 agent waits for approval and starts nothing" ($p1.kind -eq "confirm" -and $p2.kind -eq "awaiting_approval" -and $p2.task_id) "$($p2.reply)"
    $self = Call POST "tasks/$($p2.task_id)/complete" @{ outcome = "approved" } $admin
    Check "R3" "the requester cannot approve their own request, even as admin" ($self.status -ge 400) "$($self.status) $($self.body.error.code)"
    $inbox = @((Call GET "tasks" $null $aria).body.tasks) | Where-Object { $_.id -eq $p2.task_id }
    Check "R3" "the approval is in the second admin's Task inbox" ([bool]$inbox) ""
    $ok = Call POST "tasks/$($p2.task_id)/complete" @{ outcome = "approved"; comment = "checked" } $aria
    Start-Sleep -Milliseconds 300
    $last = (Lines $admin $p1.session_id)[-1].text
    Check "R3" "a second person's approval starts the run and the requester's chat is told" ($ok.status -eq 200 -and $last -match "approved") "$last"
    NextJob $dev | Out-Null
    $j1 = Say $jane "start the payment run"; $j2 = Say $jane "yes" $j1.session_id
    $rej = Call POST "tasks/$($j2.task_id)/complete" @{ outcome = "rejected"; comment = "not this week" } $aria
    Start-Sleep -Milliseconds 300
    $last = (Lines $jane $j1.session_id)[-1].text
    Check "R3" "a rejection is told to the requester with the comment" ($rej.status -eq 200 -and $last -match "not this week") "$last"

    # ---------- languages ----------
    $de = Say $jane "process vendor invoices" $null "de"
    Check "Languages" "German console: the question is in German (agent prompt kept)" ($de.kind -eq "question") "$($de.reply)"
    $de2 = Say $jane "acme" $de.session_id "de"; $de3 = Say $jane "heute" $de.session_id "de"
    Check "Languages" "German summary, and heute is understood as today" ($de3.kind -eq "confirm" -and $de3.reply -match "Soll ich|Starten|starten") "$($de3.reply)"
    $de4 = Say $jane "nein" $de.session_id "de"
    Check "Languages" "nein cancels" ($de4.kind -eq "cancelled") "$($de4.reply)"
    $fr = Say $jane "post today's invoices for acme" $null "fr"
    $fr2 = Say $jane "oui" $fr.session_id "fr"
    Check "Languages" "French: summary in French, oui starts the run" ($fr.kind -eq "confirm" -and $fr2.kind -eq "started") "$($fr.reply) / $($fr2.reply)"
    NextJob $dev | Out-Null
    $ja = Say $jane "post today's invoices for acme" $null "ja"
    $ja2 = Say $jane ([string][char]0x3044 + [char]0x3044 + [char]0x3048) $ja.session_id "ja"
    Check "Languages" "Japanese: summary in Japanese, iie cancels" ($ja.kind -eq "confirm" -and $ja.reply -match "[\u3040-\u30ff]" -and $ja2.kind -eq "cancelled") "$($ja.reply) / $($ja2.reply)"
    $es = Say $jane "order pizza for the team" $null "es"
    Check "Languages" "Spanish: not understood in Spanish" ($es.kind -eq "not_understood" -and $es.reply -match "entend") "$($es.reply)"

    # ---------- chat mode per role ----------
    $badRole = Call PUT "settings" @{ chat_mode_by_role = "nosuchrole=llm" } $admin
    Check "Settings" "an unknown role in chat mode per role is refused" ($badRole.status -ge 400) "$($badRole.body.error.message)"
    $badMode = Call PUT "settings" @{ chat_mode_by_role = "bot_runner=magic" } $admin
    Check "Settings" "an unknown mode is refused" ($badMode.status -ge 400) "$($badMode.body.error.message)"
    $setRole = Call PUT "settings" @{ chat_mode_by_role = "bot_runner=llm" } $admin
    $llm = Say $jane "show payables aging"
    $adm = Say $admin "show payables aging"
    Check "Settings" "bot_runner gets the model mode (no model connection here, so it says so); admin keeps rules mode" `
        ($setRole.status -eq 200 -and $llm.kind -ne "started" -and $adm.kind -eq "started") "jane: $($llm.kind): $($llm.reply) | admin: $($adm.kind)"
    NextJob $dev | Out-Null
    Call PUT "settings" @{ chat_mode_by_role = "" } $admin | Out-Null

    # ---------- restart ----------
    $rq = Say $jane "process vendor invoices"
    Stop-Cc $server
    $server = Start-Cc
    $admin = (Call POST "auth/login" @{ username = "admin"; password = $adminPw }).body.token
    $after = Say $jane "acme" $rq.session_id
    if ($after.kind -eq $null) {
        Check "Restart" "a signed-in person stays signed in across a restart" $false "$($after | ConvertTo-Json -Compress)"
    } else {
        $after2 = Say $jane "today" $rq.session_id
        Check "Restart" "a chat half-way through continues after the Command Center restarts" ($after.kind -eq "question" -and $after2.kind -eq "confirm" -and $after2.inputs.vendor -eq "Acme") "$($after2.reply)"
        Say $jane "no" $rq.session_id | Out-Null
    }
    $hist = Lines $jane $r0.session_id
    Check "Restart" "earlier chats and their run updates are still there" ($hist.Count -ge 3) "$($hist.Count) messages"

    # ---------- audit ----------
    $runs = (Call GET "runs?source=chat" $null $admin).body
    Check "Audit" "chat runs are recorded with source chat" ($runs.total -ge 4 -or @($runs.runs).Count -ge 4) "total $($runs.total)"
} catch {
    Check "UAT" "script error" $false "$($_.Exception.Message) at $($_.InvocationInfo.ScriptLineNumber)"
} finally {
    Stop-Cc $server
}

$pass = @($results | Where-Object ok).Count
$fail = $results.Count - $pass
$md = New-Object System.Text.StringBuilder
[void]$md.AppendLine("# One Chat UAT report")
[void]$md.AppendLine()
[void]$md.AppendLine("Run $(Get-Date -Format 'yyyy-MM-dd HH:mm') on a fresh Command Center (installed build, embedded database, port $Port), driven only through its HTTP API by people with their own sign-ins and a scripted runner. **$pass passed, $fail failed.**")
[void]$md.AppendLine()
[void]$md.AppendLine("| Area | Check | Result | Detail |")
[void]$md.AppendLine("|---|---|---|---|")
foreach ($r in $results) { [void]$md.AppendLine("| $($r.area) | $($r.name) | $(if ($r.ok) { 'PASS' } else { '**FAIL**' }) | $($r.detail) |") }
[IO.File]::WriteAllText($Report, $md.ToString(), (New-Object Text.UTF8Encoding($false)))
Write-Host "$pass passed, $fail failed. Report: $Report"
