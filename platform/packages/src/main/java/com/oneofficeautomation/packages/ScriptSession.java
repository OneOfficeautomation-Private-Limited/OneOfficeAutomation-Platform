package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * G4 (AA parity, 22 Nov 2026): a Python, Node.js, VBScript or .NET (PowerShell) process kept running for the steps of a
 * run, so what one step defines (variables, functions, loaded modules, objects) is there for the next. A small host
 * program reads one request at a time from standard input and answers with one line that starts with a marker; whatever
 * the code printed before it is the step's output. Closed with its process tree when the run ends or by Close session.
 */
final class ScriptSession implements AutoCloseable {
    static final String MARK = "__OOA__";
    static final String END = "__OOA_END__";

    final String language;
    final Process process;
    private final Writer stdin;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private final StringBuilder errors = new StringBuilder();
    private final Path host;
    /** Whether the last request in this session succeeded (the "script succeeded" condition). */
    volatile boolean lastOk = true;

    private ScriptSession(String language, Process process, Charset cs, Path host) {
        this.language = language;
        this.process = process;
        this.host = host;
        this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), cs));
        Thread out = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), cs))) {
                for (String l; (l = r.readLine()) != null; ) lines.add(l);
            } catch (IOException ignored) { }
            lines.add(END);
        }, "ooa-script-session-out");
        Thread err = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getErrorStream(), cs))) {
                for (String l; (l = r.readLine()) != null; ) synchronized (errors) { errors.append(l).append('\n'); if (errors.length() > 20000) errors.delete(0, errors.length() - 20000); }
            } catch (IOException ignored) { }
        }, "ooa-script-session-err");
        out.setDaemon(true); err.setDaemon(true);
        out.start(); err.start();
    }

    /** Starts the host for the language with the interpreter given (or the usual ones), checking it answers. */
    static ScriptSession start(ActionContext ctx, String language, String interpreter, Map<String, Object> environment) {
        String lang = language == null ? "python" : language.strip().toLowerCase(Locale.ROOT);
        List<String> tries;
        String ext, code;
        List<String> pre = List.of();
        Charset cs = StandardCharsets.UTF_8;
        switch (lang) {
            case "python" -> { tries = ProcessRunner.WINDOWS ? List.of("python", "py", "python3") : List.of("python3", "python"); ext = ".py"; code = PY_HOST; pre = List.of("-u"); }
            case "javascript" -> { tries = List.of("node"); ext = ".js"; code = JS_HOST; }
            case "vbscript" -> {
                if (!ProcessRunner.WINDOWS) throw new ActionException("SYSTEM.WINDOWS_ONLY", "VBScript runs on Windows only", "Use Python or JavaScript on this runner.");
                tries = List.of("cscript"); ext = ".vbs"; code = VBS_HOST; pre = List.of("//nologo", "//U"); cs = StandardCharsets.UTF_16LE;
            }
            case "dotnet" -> {
                if (!ProcessRunner.WINDOWS) throw new ActionException("SYSTEM.WINDOWS_ONLY", ".NET assemblies run on Windows only", "Use a Windows runner.");
                tries = List.of("powershell"); ext = ".ps1"; code = PS_HOST; pre = List.of("-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File");
            }
            default -> throw new ActionException("SCRIPT.BAD_LANGUAGE", "Unknown language " + language, "Use python, javascript or vbscript.");
        }
        if (interpreter != null && !interpreter.isBlank()) tries = ScriptPackage.interpreters(interpreter, tries);
        Path file;
        try {
            file = Files.createTempFile("ooa-session-host-", ext);
            if (lang.equals("dotnet")) Files.writeString(file, "﻿" + code, StandardCharsets.UTF_8);
            else if (lang.equals("vbscript")) Files.writeString(file, "﻿" + code, StandardCharsets.UTF_16LE);
            else Files.writeString(file, code, StandardCharsets.UTF_8);
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), "Check that the temporary folder may be written."); }
        IOException last = null;
        for (String interp : tries) {
            List<String> cmd = new ArrayList<>();
            cmd.add(interp);
            cmd.addAll(pre);
            cmd.add(file.toString());
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(ctx.workDir().toFile());
            pb.environment().putAll(ApplicationPackage.env(environment));
            if (lang.equals("python")) pb.environment().put("PYTHONIOENCODING", "utf-8");
            try {
                ScriptSession s = new ScriptSession(lang, pb.start(), cs, file);
                Map<String, Object> hello = s.send(ctx, lang.equals("vbscript") ? "eval\n\"ready\"\n" + END : JsonLite.write(Map.of("op", "ping")), 30);
                if (!Boolean.TRUE.equals(hello.get("ok"))) { s.close(); throw new ActionException("SCRIPT.FAILED", lang + " did not start: " + hello.get("message"), "Check the interpreter."); }
                return s;
            } catch (IOException e) { last = e; }
            catch (ActionException e) {
                if (!"SCRIPT.SESSION_ENDED".equals(e.code())) throw e;
                last = new IOException(e.getMessage());
            }
        }
        try { Files.deleteIfExists(file); } catch (IOException ignored) { }
        throw new ActionException("SCRIPT.INTERPRETER_NOT_FOUND", "Could not start " + String.join(" or ", tries) + (last == null ? "" : ": " + last.getMessage()),
                "Install it on the runner, or give its program in interpreter.");
    }

    /** Sends one request and waits for the answer; what was printed before it is the output. */
    @SuppressWarnings("unchecked")
    synchronized Map<String, Object> send(ActionContext ctx, String request, double timeoutSeconds) {
        if (!process.isAlive()) throw new ActionException("SCRIPT.SESSION_ENDED", "The " + language + " session has ended", "Open the session again.");
        try {
            stdin.write(request);
            stdin.write("\n");
            stdin.flush();
        } catch (IOException e) { throw new ActionException("SCRIPT.SESSION_ENDED", "The " + language + " session has ended: " + e.getMessage(), "Open the session again."); }
        StringBuilder printed = new StringBuilder();
        long end = System.currentTimeMillis() + (long) (Math.max(1, timeoutSeconds) * 1000);
        while (true) {
            String l;
            try { l = lines.poll(200, TimeUnit.MILLISECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ActionException("RUN.CANCELLED", "Interrupted", "The run was stopped."); }
            if (l == null) {
                if (ctx != null) ctx.checkCancelled();
                if (System.currentTimeMillis() > end) {
                    close();
                    throw new ActionException("SCRIPT.TIMEOUT", "The " + language + " code did not finish within " + (long) timeoutSeconds + " s; the session was closed", "Raise timeout_seconds, or check the code for a loop that never ends.", true);
                }
                continue;
            }
            if (l.equals(END)) {
                lines.add(END);
                throw new ActionException("SCRIPT.SESSION_ENDED", "The " + language + " session ended: " + ApplicationPackage.tail(errorOutput()), "Open the session again; the code may have called exit.");
            }
            int at = l.indexOf(MARK);
            if (at < 0) { printed.append(l).append('\n'); continue; }
            if (at > 0) printed.append(l, 0, at).append('\n');
            Map<String, Object> m;
            try { m = new LinkedHashMap<>((Map<String, Object>) JsonLite.parse(l.substring(at + MARK.length()))); }
            catch (RuntimeException e) { throw new ActionException("SCRIPT.BAD_RESULT", "The " + language + " session sent an answer that could not be read", "Return numbers, text, lists or dictionaries."); }
            String extra = m.get("output") == null ? "" : String.valueOf(m.get("output"));
            m.put("output", printed + extra);
            lastOk = Boolean.TRUE.equals(m.get("ok"));
            return m;
        }
    }

    String errorOutput() { synchronized (errors) { return errors.toString(); } }

    @Override public void close() {
        try { stdin.close(); } catch (IOException ignored) { }
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try { Files.deleteIfExists(host); } catch (IOException ignored) { }
    }

    /** VBScript text for a value: text quoted, numbers and true/false as they are, Null for nothing. */
    static String vbsLiteral(Object v) {
        if (v == null) return "Null";
        if (v instanceof Boolean b) return b ? "True" : "False";
        if (v instanceof Number) return new java.math.BigDecimal(v.toString()).toPlainString();
        return "\"" + String.valueOf(v).replace("\"", "\"\"").replace("\r\n", "\" & vbCrLf & \"").replace("\n", "\" & vbLf & \"") + "\"";
    }

    // ------------------------------------------------------------------------------------------------ hosts

    static final String PY_HOST = """
            import sys, json, ast, traceback, importlib.util
            MARK = '__OOA__'
            G = {'__name__': '__ooa_session__', '__builtins__': __builtins__}
            def plain(v):
                try:
                    json.dumps(v)
                    return v
                except Exception:
                    return repr(v)
            def answer(d):
                sys.stdout.flush()
                sys.stdout.write(MARK + json.dumps(d, default=str) + '\\n')
                sys.stdout.flush()
            for line in sys.stdin:
                line = line.strip()
                if not line:
                    continue
                try:
                    req = json.loads(line)
                    op = req.get('op')
                    res = None
                    if op == 'ping':
                        res = 'ready'
                    elif op == 'exec':
                        tree = ast.parse(req.get('code', ''), '<agent>', 'exec')
                        last = None
                        if tree.body and isinstance(tree.body[-1], ast.Expr):
                            last = ast.Expression(tree.body.pop().value)
                        exec(compile(tree, '<agent>', 'exec'), G)
                        if last is not None:
                            res = eval(compile(last, '<agent>', 'eval'), G)
                    elif op == 'file':
                        path = req['path']
                        with open(path, encoding='utf-8') as f:
                            exec(compile(f.read(), path, 'exec'), G)
                    elif op == 'call':
                        parts = req['function'].split('.')
                        if parts[0] not in G:
                            raise NameError(parts[0] + ' is not defined in the session')
                        target = G[parts[0]]
                        for p in parts[1:]:
                            target = getattr(target, p)
                        res = target(*req.get('args', []), **req.get('kwargs', {}))
                    else:
                        raise ValueError('unknown request ' + str(op))
                    answer({'ok': True, 'result': plain(res)})
                except BaseException as e:
                    if isinstance(e, SystemExit):
                        answer({'ok': False, 'type': 'SystemExit', 'message': 'the code called exit; the session goes on'})
                        continue
                    answer({'ok': False, 'type': type(e).__name__, 'message': str(e), 'trace': traceback.format_exc()[-2000:]})
            """;

    static final String JS_HOST = """
            const vm = require('vm'), fs = require('fs'), readline = require('readline');
            const MARK = '__OOA__';
            const sandbox = { require, process, Buffer, console, setTimeout, clearTimeout, setInterval, clearInterval, URL, TextEncoder, TextDecoder };
            sandbox.globalThis = sandbox;
            const ctx = vm.createContext(sandbox);
            const answer = (d) => process.stdout.write(MARK + JSON.stringify(d, (k, v) => typeof v === 'bigint' ? String(v) : v) + '\\n');
            const plain = (v) => { try { JSON.stringify(v); return v === undefined ? null : v; } catch (e) { return String(v); } };
            const rl = readline.createInterface({ input: process.stdin });
            let chain = Promise.resolve();
            rl.on('line', (line) => { chain = chain.then(() => handle(line)); });
            async function handle(line) {
              if (!line.trim()) return;
              try {
                const req = JSON.parse(line);
                let res = null;
                if (req.op === 'ping') res = 'ready';
                else if (req.op === 'exec') res = await vm.runInContext(req.code || '', ctx, { filename: 'agent.js' });
                else if (req.op === 'file') res = await vm.runInContext(fs.readFileSync(req.path, 'utf8'), ctx, { filename: req.path });
                else if (req.op === 'call') {
                  let owner = ctx, fn = ctx;
                  for (const part of req.function.split('.')) { owner = fn; fn = fn == null ? undefined : fn[part]; }
                  if (typeof fn !== 'function') throw new TypeError(req.function + ' is not a function in the session');
                  res = await fn.apply(owner, req.args || []);
                } else throw new Error('unknown request ' + req.op);
                answer({ ok: true, result: plain(res) });
              } catch (e) {
                answer({ ok: false, type: (e && e.name) || 'Error', message: String(e && e.message !== undefined ? e.message : e), trace: String((e && e.stack) || '').slice(-2000) });
              }
            }
            """;

    /** VBScript: an operation line (exec or eval), the code, then the end line; answers in JSON built by hand. */
    static final String VBS_HOST = """
            Option Explicit
            Dim op, code, l, res, isObj
            Function J(s)
              Dim t: t = CStr(s)
              t = Replace(t, Chr(92), Chr(92) & Chr(92))
              t = Replace(t, Chr(34), Chr(92) & Chr(34))
              t = Replace(t, vbCr, Chr(92) & "r")
              t = Replace(t, vbLf, Chr(92) & "n")
              t = Replace(t, vbTab, Chr(92) & "t")
              J = t
            End Function
            Function S(x)
              S = Chr(34) & J(x) & Chr(34)
            End Function
            Sub Answer(body)
              WScript.StdOut.WriteLine "__OOA__{" & body & "}"
            End Sub
            Do While Not WScript.StdIn.AtEndOfStream
              op = Trim(WScript.StdIn.ReadLine)
              code = ""
              Do While Not WScript.StdIn.AtEndOfStream
                l = WScript.StdIn.ReadLine
                If l = "__OOA_END__" Then Exit Do
                code = code & l & vbCrLf
              Loop
              If op <> "" Then
                res = Empty: isObj = False
                On Error Resume Next
                Err.Clear
                If op = "exec" Then
                  ExecuteGlobal code
                Else
                  res = Eval(code)
                  If Err.Number = 450 Then Err.Clear: isObj = True
                End If
                If Err.Number <> 0 Then
                  Answer S("ok") & ":false," & S("type") & ":" & S(Err.Source) & "," & S("message") & ":" & S(Err.Description & " (" & Err.Number & ")")
                Else
                  On Error Resume Next
                  If isObj Then
                    Answer S("ok") & ":true," & S("result") & ":" & S("(object)")
                  ElseIf IsEmpty(res) Or IsNull(res) Then
                    Answer S("ok") & ":true," & S("result") & ":null"
                  ElseIf VarType(res) = vbBoolean Then
                    Answer S("ok") & ":true," & S("result") & ":" & LCase(CStr(res))
                  ElseIf IsNumeric(res) And VarType(res) <> vbString Then
                    Answer S("ok") & ":true," & S("result") & ":" & Replace(CStr(res), ",", ".")
                  Else
                    Answer S("ok") & ":true," & S("result") & ":" & S(res)
                  End If
                End If
                On Error GoTo 0
              End If
            Loop
            """;

    /**
     * .NET through PowerShell: load an assembly, create objects kept by name, call static or object methods with typed
     * and named arguments, and describe an assembly's public types and methods.
     */
    static final String PS_HOST = """
            $ErrorActionPreference = 'Stop'
            try { [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false } catch { }
            try { [Console]::InputEncoding = New-Object System.Text.UTF8Encoding $false } catch { }
            $asm = $null; $objects = @{}
            function Answer($d) { [Console]::Out.WriteLine('__OOA__' + (ConvertTo-Json -InputObject $d -Depth 6 -Compress)); [Console]::Out.Flush() }
            function Conv($v, [string]$t) {
              switch ($t) {
                'text' { return [string]$v } 'int' { return [int]$v } 'long' { return [long]$v } 'double' { return [double]$v } 'decimal' { return [decimal]$v }
                'bool' { return [bool]::Parse([string]$v) } 'byte' { return [byte]$v } 'datetime' { return [datetime]::Parse([string]$v, [Globalization.CultureInfo]::InvariantCulture) }
                'bytes' { return [Convert]::FromBase64String([string]$v) } 'text_list' { return [string[]]@($v) } 'number_list' { return [double[]]@($v) }
                'list' { $l = New-Object 'System.Collections.Generic.List[object]'; foreach ($x in @($v)) { $l.Add($x) }; return ,$l }
                'dictionary' { $d = New-Object 'System.Collections.Generic.Dictionary[string,object]'; foreach ($p in $v.PSObject.Properties) { $d[$p.Name] = $p.Value }; return ,$d }
                default { return $v }
              }
            }
            function Find-Type([string]$name) {
              if ($script:asm) { $t = $script:asm.GetType($name, $false, $true); if ($t) { return $t } }
              foreach ($a in [AppDomain]::CurrentDomain.GetAssemblies()) { $t = $a.GetType($name, $false, $true); if ($t) { return $t } }
              throw "No type named $name in the assembly or the .NET Framework"
            }
            function Plain($r) {
              if ($null -eq $r) { return $null }
              if ($r -is [string] -or $r -is [ValueType]) { if ($r -is [datetime]) { return $r.ToString('s') }; return $r }
              if ($r -is [byte[]]) { return [Convert]::ToBase64String($r) }
              if ($r -is [Collections.IDictionary]) { $h = [ordered]@{}; foreach ($k in $r.Keys) { $h[[string]$k] = Plain $r[$k] }; return $h }
              if ($r -is [Collections.IEnumerable]) { return ,@($r | ForEach-Object { Plain $_ }) }
              return $r.ToString()
            }
            function Args-For($m, $req) {
              $ps = $m.GetParameters(); $named = $req.named
              $pos = @(); if ($null -ne $req.args) { $pos = @($req.args) }
              $types = @(); if ($null -ne $req.types) { $types = @($req.types) }
              if ($pos.Count -gt $ps.Count) { return $null }
              $out = New-Object object[] $ps.Count
              for ($i = 0; $i -lt $ps.Count; $i++) {
                $p = $ps[$i]; $t = if ($i -lt $types.Count) { [string]$types[$i] } else { 'auto' }
                if ($i -lt $pos.Count) { $v = Conv $pos[$i] $t }
                elseif ($named -and $named.PSObject.Properties[$p.Name]) { $v = $named.PSObject.Properties[$p.Name].Value }
                elseif ($p.IsOptional) { $v = $p.DefaultValue }
                else { return $null }
                try { $out[$i] = if ($null -eq $v) { $null } else { [Management.Automation.LanguagePrimitives]::ConvertTo($v, $p.ParameterType) } } catch { return $null }
              }
              return ,$out
            }
            while ($null -ne ($line = [Console]::In.ReadLine())) {
              if (-not $line.Trim()) { continue }
              try {
                $req = ConvertFrom-Json $line; $res = $null
                switch ($req.op) {
                  'ping' { $res = 'ready' }
                  'load' { $script:asm = [Reflection.Assembly]::LoadFrom($req.path); $res = $script:asm.FullName }
                  'describe' {
                    $a = if ($req.path) { [Reflection.Assembly]::LoadFrom($req.path) } else { $script:asm }
                    $res = @(); foreach ($t in $a.GetExportedTypes()) {
                      $ms = @(); foreach ($m in $t.GetMethods([Reflection.BindingFlags]'Public,Instance,Static,DeclaredOnly')) { if (-not $m.IsSpecialName) {
                        $ms += [ordered]@{ name = $m.Name; static = $m.IsStatic; returns = $m.ReturnType.Name; parameters = @($m.GetParameters() | ForEach-Object { [ordered]@{ name = $_.Name; type = $_.ParameterType.Name; optional = $_.IsOptional } }) } } }
                      $res += [ordered]@{ type = $t.FullName; methods = $ms }
                    }
                  }
                  'new' {
                    $t = Find-Type $req.type; $made = $null
                    foreach ($c in $t.GetConstructors()) { $a = Args-For $c $req; if ($null -ne $a) { $made = $c.Invoke($a); break } }
                    if ($null -eq $made) { throw "No constructor of $($req.type) takes these arguments" }
                    $objects[$req.name] = $made; $res = $req.name
                  }
                  'call' {
                    $target = $null
                    if ($req.object) { if (-not $objects.ContainsKey($req.object)) { throw "No object named $($req.object) in the session; create it first" }; $target = $objects[$req.object]; $t = $target.GetType() }
                    else { $t = Find-Type $req.type }
                    $done = $false
                    foreach ($m in $t.GetMethods()) { if ($m.Name -ieq $req.method -and ($m.IsStatic -eq ($null -eq $target))) {
                      $a = Args-For $m $req
                      if ($null -ne $a) { $res = Plain ($m.Invoke($target, $a)); $done = $true; break }
                    } }
                    if (-not $done) { throw "No method $($req.method) of $($t.FullName) takes these arguments" }
                  }
                  default { throw "unknown request $($req.op)" }
                }
                Answer @{ ok = $true; result = $res }
              } catch {
                $e = $_.Exception; while ($e.InnerException) { $e = $e.InnerException }
                Answer @{ ok = $false; type = $e.GetType().Name; message = $e.Message }
              }
            }
            """;
}
