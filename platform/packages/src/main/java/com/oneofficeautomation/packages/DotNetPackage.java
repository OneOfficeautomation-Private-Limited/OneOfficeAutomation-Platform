package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Code in other languages from an agent (gap G41): inline C# and VB.NET, a method of a .NET assembly, a COM object's
 * method or property, and a static or instance method of a Java class from JAR files.
 * <p>
 * Each call runs in a process of its own (Windows PowerShell for .NET and COM, a separate Java for Java), never inside
 * the runner, so code that crashes or calls exit cannot take the runner with it, and the step's time limit stops it.
 * Values go in as JSON on standard input and the result comes back as JSON after a marker; anything the code prints
 * is returned as output. C# and VB.NET are compiled by the .NET Framework that ships with Windows (C# 5, VB 11).
 */
@OoaPackage(id = "ooa.dotnet", name = ".NET, Java and COM", version = "1.0.0",
        description = "Run inline C# or VB.NET code, and call methods of .NET assemblies, COM objects and Java classes.")
public final class DotNetPackage {
    static final String MARK = "__OOA_RESULT__";

    /** PowerShell that turns the JSON payload into .NET values and a result into JSON (dictionaries, lists, plain values; anything else as text). */
    static final String PS_COMMON = """
            $ErrorActionPreference = 'Stop'
            function ConvertTo-Plain($v) {
              if ($null -eq $v) { return $null }
              if ($v -is [System.Management.Automation.PSCustomObject]) {
                $d = New-Object 'System.Collections.Generic.Dictionary[string,object]'
                foreach ($p in $v.PSObject.Properties) { $x = ConvertTo-Plain $p.Value; if ($null -eq $x) { $d.Add($p.Name, $null) } else { $d.Add($p.Name, $x.PSObject.BaseObject) } }
                return ,$d
              }
              if ($v -is [System.Array]) {
                $l = New-Object 'System.Collections.Generic.List[object]'
                foreach ($e in $v) { $x = ConvertTo-Plain $e; if ($null -eq $x) { $l.Add($null) } else { $l.Add($x.PSObject.BaseObject) } }
                return ,$l
              }
              return $v
            }
            function ConvertTo-Out($v, [int]$depth) {
              if ($null -eq $v -or $v -is [System.DBNull]) { return $null }
              if ($v -is [string] -or $v -is [bool] -or $v -is [char] -or $v -is [guid]) { return [string]$v }
              if ($v -is [datetime]) { return $v.ToString('o') }
              if ($v -is [System.ValueType] -and ($v -is [int] -or $v -is [long] -or $v -is [double] -or $v -is [decimal] -or $v -is [single] -or $v -is [short] -or $v -is [byte] -or $v -is [uint32] -or $v -is [uint64])) { return $v }
              if ($depth -gt 20) { return [string]$v }
              if ($v -is [System.Collections.IDictionary]) {
                $d = [ordered]@{}
                foreach ($k in $v.Keys) { $d[[string]$k] = ConvertTo-Out $v[$k] ($depth + 1) }
                return $d
              }
              if ($v -is [System.Collections.IEnumerable] -and -not ($v -is [System.__ComObject])) {
                $l = New-Object 'System.Collections.Generic.List[object]'
                foreach ($x in $v) { if ($l.Count -ge 10000) { break }; $l.Add((ConvertTo-Out $x ($depth + 1))) }
                return ,$l.ToArray()
              }
              return [string]$v
            }
            function Send-Result($ok, $value, $message, $kind) {
              $o = [ordered]@{ ok = $ok; result = $value; message = $message; kind = $kind }
              [Console]::Out.WriteLine('__OOA_RESULT__' + (ConvertTo-Json -InputObject $o -Depth 30 -Compress))
            }
            function Get-Args($list) {
              $l = New-Object 'System.Collections.Generic.List[object]'
              if ($null -ne $list) { foreach ($e in @($list)) { $x = ConvertTo-Plain $e; if ($null -eq $x) { $l.Add($null) } else { $l.Add($x.PSObject.BaseObject) } } }
              return ,$l.ToArray()
            }
            function Get-Inner($e) { while ($e.InnerException) { $e = $e.InnerException }; return $e }
            $payload = [Console]::In.ReadToEnd() | ConvertFrom-Json
            """;

    static final String PS_COMPILE = """
            try {
              $refs = @(); foreach ($r in $payload.references) { $refs += [string]$r }
              $lang = [string]$payload.language
              try {
                if ($refs.Count) { Add-Type -TypeDefinition $payload.source -Language $lang -ReferencedAssemblies $refs -IgnoreWarnings }
                else { Add-Type -TypeDefinition $payload.source -Language $lang -IgnoreWarnings }
              } catch {
                $msgs = @(); foreach ($er in @($Error)) { if ($er.Exception -and $er.Exception.Message -and $msgs.Count -lt 20) { $msgs += $er.Exception.Message } }
                Send-Result $false $null (($msgs | Select-Object -Unique) -join "`n") 'compile'; exit 0
              }
              $a = ConvertTo-Plain $payload.args
              if ($null -eq $a) { $a = New-Object 'System.Collections.Generic.Dictionary[string,object]' }
              $r = [OoaInline.Code]::Run($a)
              Send-Result $true (ConvertTo-Out $r 0) $null $null
            } catch {
              $inner = Get-Inner $_.Exception
              Send-Result $false $null ($inner.GetType().FullName + ': ' + $inner.Message) 'run'
            }
            """;

    static final String PS_DOTNET = """
            try {
              $asmName = [string]$payload.assembly
              $t = $null
              if ($asmName) {
                if (Test-Path -LiteralPath $asmName) { $asm = [System.Reflection.Assembly]::LoadFrom((Resolve-Path -LiteralPath $asmName).Path) }
                else { try { $asm = [System.Reflection.Assembly]::Load($asmName) } catch { $asm = [System.Reflection.Assembly]::LoadWithPartialName($asmName) } }
                if ($null -eq $asm) { Send-Result $false $null ('Assembly not found: ' + $asmName) 'not_found'; exit 0 }
                $t = $asm.GetType([string]$payload.type, $false, $true)
              } else {
                $t = [System.Type]::GetType([string]$payload.type, $false, $true)
                if ($null -eq $t) { foreach ($x in [AppDomain]::CurrentDomain.GetAssemblies()) { $t = $x.GetType([string]$payload.type, $false, $true); if ($t) { break } } }
              }
              if ($null -eq $t) { Send-Result $false $null ('Type not found: ' + $payload.type) 'not_found'; exit 0 }
              $argv = Get-Args $payload.args
              $flags = [System.Reflection.BindingFlags]'Public,IgnoreCase'
              $target = $null
              if ($payload.create_instance) {
                $ctor = Get-Args $payload.constructor_args
                $target = [System.Activator]::CreateInstance($t, $ctor)
                $flags = $flags -bor [System.Reflection.BindingFlags]::Instance
              } else { $flags = $flags -bor [System.Reflection.BindingFlags]::Static }
              $flags = $flags -bor $(if ($payload.kind -eq 'get_property') { [System.Reflection.BindingFlags]::GetProperty -bor [System.Reflection.BindingFlags]::GetField } else { [System.Reflection.BindingFlags]::InvokeMethod })
              try { $r = $t.InvokeMember([string]$payload.member, $flags, $null, $target, $argv) }
              catch [System.MissingMemberException] { Send-Result $false $null ((Get-Inner $_.Exception).Message) 'not_found'; exit 0 }
              Send-Result $true (ConvertTo-Out $r 0) $null $null
              if ($target -is [System.IDisposable]) { $target.Dispose() }
            } catch {
              $inner = Get-Inner $_.Exception
              Send-Result $false $null ($inner.GetType().FullName + ': ' + $inner.Message) 'run'
            }
            """;

    static final String PS_COM = """
            $o = $null
            try {
              try { $o = New-Object -ComObject ([string]$payload.prog_id) }
              catch { Send-Result $false $null ('No COM object ' + $payload.prog_id + ': ' + (Get-Inner $_.Exception).Message) 'not_found'; exit 0 }
              $target = $o
              foreach ($part in @($payload.path)) { if ($part) { $target = [System.__ComObject].InvokeMember([string]$part, [System.Reflection.BindingFlags]::GetProperty, $null, $target, $null) } }
              $argv = Get-Args $payload.args
              $flag = if ($payload.kind -eq 'get_property') { [System.Reflection.BindingFlags]::GetProperty } elseif ($payload.kind -eq 'set_property') { [System.Reflection.BindingFlags]::SetProperty } else { [System.Reflection.BindingFlags]::InvokeMethod }
              $r = [System.__ComObject].InvokeMember([string]$payload.member, $flag, $null, $target, $argv)
              Send-Result $true (ConvertTo-Out $r 0) $null $null
            } catch {
              $inner = Get-Inner $_.Exception
              Send-Result $false $null ($inner.GetType().FullName + ': ' + $inner.Message) 'run'
            } finally {
              if ($o) {
                if ($payload.quit) { try { [System.__ComObject].InvokeMember('Quit', [System.Reflection.BindingFlags]::InvokeMethod, $null, $o, $null) | Out-Null } catch { } }
                [void][System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($o)
              }
            }
            """;

    /** C# around the step's code: a static Run(args) in OoaInline.Code; members go next to it. */
    static String csharpSource(String code, String usings, String members) {
        StringBuilder s = new StringBuilder("using System;\nusing System.Collections.Generic;\nusing System.Linq;\nusing System.Text;\nusing System.IO;\n");
        for (String u : lines(usings)) s.append(u.startsWith("using ") ? u : "using " + u).append(u.endsWith(";") ? "" : ";").append('\n');
        s.append("namespace OoaInline {\npublic static class Code {\npublic static object Run(IDictionary<string, object> args) {\n")
                .append(code).append("\n#pragma warning disable 0162\nreturn null;\n}\n");
        if (members != null && !members.isBlank()) s.append(members).append('\n');
        return s.append("}\n}\n").toString();
    }

    static String vbSource(String code, String imports, String members) {
        StringBuilder s = new StringBuilder("Imports System\nImports System.Collections.Generic\nImports System.Linq\nImports System.Text\nImports System.IO\n");
        for (String u : lines(imports)) s.append(u.regionMatches(true, 0, "Imports ", 0, 8) ? u : "Imports " + u).append('\n');
        s.append("Namespace OoaInline\nPublic Module Code\nPublic Function Run(args As IDictionary(Of String, Object)) As Object\n")
                .append(code).append("\nReturn Nothing\nEnd Function\n");
        if (members != null && !members.isBlank()) s.append(members).append('\n');
        return s.append("End Module\nEnd Namespace\n").toString();
    }

    private static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        if (text != null) for (String l : text.split("[\\r\\n,]+")) if (!l.isBlank()) out.add(l.strip());
        return out;
    }

    /** Runs a PowerShell part with the payload on standard input and reads the result after the marker. */
    static Map<String, Object> powershell(ActionContext ctx, String part, Map<String, Object> payload, double timeout, boolean fail, String codePrefix) {
        if (!ProcessRunner.WINDOWS) throw new ActionException("SYSTEM.WINDOWS_ONLY", ".NET and COM calls run only on Windows", "Run this agent on a Windows runner.");
        Path ps;
        try {
            ps = Files.createTempFile("ooa-dotnet-", ".ps1");
            Files.writeString(ps, "\uFEFF" + ScriptPackage.UTF8_PREAMBLE + "\n" + PS_COMMON + part, StandardCharsets.UTF_8);
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot write the PowerShell file: " + e.getMessage(), null, true); }
        try {
            List<String> cmd = List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", ps.toString());
            ProcessRunner.Result r = ProcessRunner.run(ctx, cmd, ctx.workDir(), JsonLite.write(payload), timeout, null, StandardCharsets.UTF_8);
            return result(r, fail, codePrefix);
        } finally {
            try { Files.deleteIfExists(ps); } catch (IOException ignored) { }
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> result(ProcessRunner.Result r, boolean fail, String codePrefix) {
        String out = r.stdout();
        int at = out.lastIndexOf(MARK);
        if (at < 0) throw new ActionException(codePrefix + ".FAILED", "The call returned no result (exit code " + r.exitCode() + "): "
                + ApplicationPackage.tail(r.stderr().isBlank() ? out : r.stderr()), "Check the code or the names given.");
        Map<String, Object> res = (Map<String, Object>) JsonLite.parse(out.substring(at + MARK.length()).split("\\r?\\n", 2)[0]);
        String printed = (out.substring(0, at) + (r.stderr().isBlank() ? "" : "\n" + r.stderr())).strip();
        Map<String, Object> m = new LinkedHashMap<>();
        if (Boolean.TRUE.equals(res.get("ok"))) {
            m.put("result", res.get("result")); m.put("output", printed); m.put("error", "");
            return m;
        }
        String msg = String.valueOf(res.get("message")), kind = String.valueOf(res.get("kind"));
        String code = codePrefix + ("compile".equals(kind) ? ".COMPILE_FAILED" : "not_found".equals(kind) ? ".NOT_FOUND" : ".ERROR");
        if (fail || "compile".equals(kind)) throw new ActionException(code, msg, "compile".equals(kind) ? "Fix the code: the compiler's messages say where."
                : "not_found".equals(kind) ? "Check the names: assembly, type, ProgID, class and member." : "Fix the code or its arguments, or turn off fail on error to read the error instead.");
        m.put("result", null); m.put("output", printed); m.put("error", msg);
        return m;
    }

    private static final String D_FAIL = "Fail the step when the code throws; when off, the error goes to 'error' and the run goes on.";
    private static final String D_TIMEOUT = "Stop the call after this many seconds.";

    @Action(id = "run_csharp", name = "Run C# code", category = "Code", timeoutSeconds = 3600,
            description = "Run C# statements (the body of a method that gets args and returns a value); compiled by the .NET Framework on Windows.",
            panel = "code, arguments | usings, members, references, timeout_seconds, fail_on_error")
    @Output(name = "result", description = "What the code returns (text, numbers, true/false, lists and dictionaries; other objects as text).")
    @Output(name = "output", type = OoaType.STRING, description = "What the code wrote with Console.WriteLine.")
    @Output(name = "error", type = OoaType.STRING, description = "The exception, when fail on error is off.")
    public Map<String, Object> runCsharp(ActionContext ctx,
            @Input(name = "code", type = OoaType.STRING, example = "var n = Convert.ToInt32(args[\"count\"]);\nreturn n * 2;", description = "C# statements; args is an IDictionary<string, object> of the arguments; return a value.") String code,
            @Input(name = "arguments", type = OoaType.DICTIONARY, required = false, description = "Values the code reads as args[\"name\"].") Map<String, Object> args,
            @Input(name = "usings", type = OoaType.STRING, required = false, example = "System.Globalization", description = "Extra namespaces, one per line (System, Collections.Generic, Linq, Text and IO are there).") String usings,
            @Input(name = "members", type = OoaType.STRING, required = false, description = "Other methods or fields of the same static class, for code used more than once.") String members,
            @Input(name = "references", type = OoaType.LIST, required = false, example = "[\"System.Xml\"]", description = "Assemblies the code needs beyond the usual ones: names such as System.Xml, or DLL paths.") List<Object> refs,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "120", min = 1, description = D_TIMEOUT) double timeout,
            @Input(name = "fail_on_error", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_FAIL) boolean fail) {
        if (code == null || code.isBlank()) throw new ActionException("DOTNET.NO_CODE", "Type the C# code", "Fill in code.");
        return powershell(ctx, PS_COMPILE, compilePayload(ctx, "CSharp", csharpSource(code, usings, members), args, refs), timeout, fail, "DOTNET");
    }

    @Action(id = "run_vbnet", name = "Run VB.NET code", category = "Code", timeoutSeconds = 3600,
            description = "Run VB.NET statements (the body of a function that gets args and returns a value); compiled by the .NET Framework on Windows.",
            panel = "code, arguments | imports, members, references, timeout_seconds, fail_on_error")
    @Output(name = "result", description = "What the code returns (text, numbers, true/false, lists and dictionaries; other objects as text).")
    @Output(name = "output", type = OoaType.STRING, description = "What the code wrote with Console.WriteLine.")
    @Output(name = "error", type = OoaType.STRING, description = "The exception, when fail on error is off.")
    public Map<String, Object> runVbNet(ActionContext ctx,
            @Input(name = "code", type = OoaType.STRING, example = "Dim n As Integer = CInt(args(\"count\"))\nReturn n * 2", description = "VB.NET statements; args is an IDictionary(Of String, Object) of the arguments; Return a value.") String code,
            @Input(name = "arguments", type = OoaType.DICTIONARY, required = false, description = "Values the code reads as args(\"name\").") Map<String, Object> args,
            @Input(name = "imports", type = OoaType.STRING, required = false, example = "System.Globalization", description = "Extra namespaces, one per line.") String imports,
            @Input(name = "members", type = OoaType.STRING, required = false, description = "Other functions or fields of the same module.") String members,
            @Input(name = "references", type = OoaType.LIST, required = false, description = "Assemblies the code needs beyond the usual ones: names or DLL paths.") List<Object> refs,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "120", min = 1, description = D_TIMEOUT) double timeout,
            @Input(name = "fail_on_error", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_FAIL) boolean fail) {
        if (code == null || code.isBlank()) throw new ActionException("DOTNET.NO_CODE", "Type the VB.NET code", "Fill in code.");
        return powershell(ctx, PS_COMPILE, compilePayload(ctx, "VisualBasic", vbSource(code, imports, members), args, refs), timeout, fail, "DOTNET");
    }

    private static Map<String, Object> compilePayload(ActionContext ctx, String language, String source, Map<String, Object> args, List<Object> refs) {
        List<Object> r = new ArrayList<>(List.of("System.Core"));
        // a DLL given by a path is resolved against the workspace, like other files
        if (refs != null) for (Object o : refs) { String s = Values.text(o).strip(); if (!s.isEmpty()) r.add(s.toLowerCase(Locale.ROOT).endsWith(".dll") ? ctx.resolvePath(s).toString() : s); }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("language", language); p.put("source", source); p.put("args", args == null ? Map.of() : args); p.put("references", r);
        return p;
    }

    @Action(id = "call_dotnet_method", name = "Call .NET method", category = ".NET", timeoutSeconds = 3600,
            description = "Call a static method or property of a .NET type, or create an object and call one of its methods (Windows).",
            panel = "assembly, type, member, kind, arguments | [Create an object first: create_instance] constructor_arguments, timeout_seconds, fail_on_error")
    @Output(name = "result", description = "What the method or property returned.")
    @Output(name = "output", type = OoaType.STRING, description = "Anything the method wrote to the console.")
    @Output(name = "error", type = OoaType.STRING, description = "The exception, when fail on error is off.")
    public Map<String, Object> callDotNet(ActionContext ctx,
            @Input(name = "assembly", type = OoaType.STRING, required = false, example = "C:/Tools/Pricing.dll", description = "DLL path or assembly name; empty for types of the .NET Framework itself (System.Math).") String assembly,
            @Input(name = "type", type = OoaType.STRING, example = "System.Math", description = "Full type name, with its namespace.") String type,
            @Input(name = "member", type = OoaType.STRING, example = "Round", description = "Method or property name.") String member,
            @Input(name = "kind", type = OoaType.STRING, required = false, defaultValue = "\"method\"", enumValues = {"method", "get_property"}, description = "Call a method, or read a property or field.") String kind,
            @Input(name = "arguments", type = OoaType.LIST, required = false, description = "Arguments in order; JSON numbers become Int32, Int64, Decimal or Double.") List<Object> args,
            @Input(name = "create_instance", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Create an object of the type first and call the member on it.") boolean instance,
            @Input(name = "constructor_arguments", type = OoaType.LIST, required = false, visibleWhen = "create_instance=true", description = "Arguments for creating the object.") List<Object> ctorArgs,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "120", min = 1, description = D_TIMEOUT) double timeout,
            @Input(name = "fail_on_error", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_FAIL) boolean fail) {
        Map<String, Object> p = new LinkedHashMap<>();
        String asm = assembly == null ? "" : assembly.strip();
        if (asm.toLowerCase(Locale.ROOT).endsWith(".dll") || asm.toLowerCase(Locale.ROOT).endsWith(".exe")) {
            Path dll = ctx.resolvePath(asm);
            if (!Files.isRegularFile(dll)) throw new ActionException("FILE.NOT_FOUND", "Assembly not found: " + dll, "Check the path.");
            asm = dll.toString();
        }
        p.put("assembly", asm); p.put("type", required(type, "type")); p.put("member", required(member, "member")); p.put("kind", kind == null ? "method" : kind);
        p.put("args", args == null ? List.of() : args); p.put("create_instance", instance); p.put("constructor_args", ctorArgs == null ? List.of() : ctorArgs);
        return powershell(ctx, PS_DOTNET, p, timeout, fail, "DOTNET");
    }

    @Action(id = "call_com_method", name = "Call COM method", category = "COM", timeoutSeconds = 3600,
            description = "Create a COM object by its ProgID and call a method or read or set a property (Windows), optionally through a path of properties.",
            panel = "prog_id, path, member, kind, arguments | quit, timeout_seconds, fail_on_error")
    @Output(name = "result", description = "What the method or property returned (objects as text).")
    @Output(name = "output", type = OoaType.STRING, description = "Anything written to the console.")
    @Output(name = "error", type = OoaType.STRING, description = "The error, when fail on error is off.")
    public Map<String, Object> callCom(ActionContext ctx,
            @Input(name = "prog_id", type = OoaType.STRING, example = "Scripting.FileSystemObject", description = "ProgID of the COM class.") String progId,
            @Input(name = "path", type = OoaType.LIST, required = false, example = "[\"Application\"]", description = "Properties to go through first, in order, e.g. Workbooks then Item.") List<Object> path,
            @Input(name = "member", type = OoaType.STRING, example = "FolderExists", description = "Method or property name.") String member,
            @Input(name = "kind", type = OoaType.STRING, required = false, defaultValue = "\"method\"", enumValues = {"method", "get_property", "set_property"}, description = "Call a method, read a property, or set one (its value is the last argument).") String kind,
            @Input(name = "arguments", type = OoaType.LIST, required = false, description = "Arguments in order.") List<Object> args,
            @Input(name = "quit", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Call the object's Quit method at the end (Office applications).") boolean quit,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "120", min = 1, description = D_TIMEOUT) double timeout,
            @Input(name = "fail_on_error", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_FAIL) boolean fail) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("prog_id", required(progId, "prog_id")); p.put("member", required(member, "member")); p.put("kind", kind == null ? "method" : kind);
        p.put("path", path == null ? List.of() : path.stream().map(Values::text).toList()); p.put("args", args == null ? List.of() : args); p.put("quit", quit);
        return powershell(ctx, PS_COM, p, timeout, fail, "COM");
    }

    @Action(id = "call_java_method", name = "Call Java method", category = "Java", timeoutSeconds = 3600,
            description = "Load JAR files and call a static method of a class, or create an object and call one of its methods, in a separate Java process.",
            panel = "jars, class_name, method, arguments | [Create an object first: create_instance] constructor_arguments, java, timeout_seconds, fail_on_error")
    @Output(name = "result", description = "What the method returned (text, numbers, true/false, lists and maps; other objects as text).")
    @Output(name = "output", type = OoaType.STRING, description = "What the method printed.")
    @Output(name = "error", type = OoaType.STRING, description = "The exception, when fail on error is off.")
    public Map<String, Object> callJava(ActionContext ctx,
            @Input(name = "jars", type = OoaType.LIST, required = false, example = "[\"lib/pricing.jar\"]", description = "JAR files or class folders to load; empty for classes of Java itself.") List<Object> jars,
            @Input(name = "class_name", type = OoaType.STRING, example = "java.lang.Math", description = "Full class name, with its package.") String cls,
            @Input(name = "method", type = OoaType.STRING, example = "max", description = "Method name; the one whose parameters fit the arguments is used.") String method,
            @Input(name = "arguments", type = OoaType.LIST, required = false, description = "Arguments in order; converted to the method's parameter types.") List<Object> args,
            @Input(name = "create_instance", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Create an object of the class first and call the method on it.") boolean instance,
            @Input(name = "constructor_arguments", type = OoaType.LIST, required = false, visibleWhen = "create_instance=true", description = "Arguments for creating the object.") List<Object> ctorArgs,
            @Input(name = "java", type = OoaType.FILE, required = false, description = "Java program to use; the runner's own Java, then java on the PATH, when empty.") String java,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "120", min = 1, description = D_TIMEOUT) double timeout,
            @Input(name = "fail_on_error", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_FAIL) boolean fail) {
        List<String> cp = new ArrayList<>();
        if (jars != null) for (Object j : jars) {
            String s = Values.text(j).strip();
            if (s.isEmpty()) continue;
            Path p = ctx.resolvePath(s);
            if (!Files.exists(p)) throw new ActionException("FILE.NOT_FOUND", "JAR not found: " + p, "Check the path.");
            cp.add(p.toString());
        }
        cp.add(codeSource(JavaCall.class)); cp.add(codeSource(JsonLite.class));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("class", required(cls, "class_name")); p.put("method", required(method, "method")); p.put("args", args == null ? List.of() : args);
        p.put("create_instance", instance); p.put("constructor_args", ctorArgs == null ? List.of() : ctorArgs);
        List<String> programs = new ArrayList<>();
        if (java != null && !java.isBlank()) programs.add(ctx.resolvePath(java).toString());
        else {
            Path own = Path.of(System.getProperty("java.home", "."), "bin", ProcessRunner.WINDOWS ? "java.exe" : "java");
            if (System.getProperty("java.home") != null && Files.isRegularFile(own)) programs.add(own.toString());
            programs.add("java");
        }
        ActionException last = null;
        for (String prog : programs) {
            try {
                List<String> cmd = List.of(prog, "-Xss4m", "-cp", String.join(File.pathSeparator, cp), JavaCall.class.getName());
                return result(ProcessRunner.run(ctx, cmd, ctx.workDir(), JsonLite.write(p), timeout, null, StandardCharsets.UTF_8), fail, "JAVA");
            } catch (ActionException e) {
                if (!"PROCESS.NOT_FOUND".equals(e.code())) throw e;
                last = e;
            }
        }
        throw new ActionException("JAVA.NOT_INSTALLED", "No Java found on this runner", "Install Java 11 or later, or give its program in java.", false,
                last == null ? null : Map.of("detail", last.getMessage()));
    }

    private static String required(String v, String name) {
        if (v == null || v.isBlank()) throw new ActionException("DOTNET.MISSING", "Fill in " + name, null);
        return v.strip();
    }

    /** The JAR or class folder a class was loaded from, for the separate Java's class path. */
    static String codeSource(Class<?> c) {
        try { return Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(); }
        catch (Exception e) { throw new ActionException("JAVA.SETUP", "Cannot find the runner's own classes for the Java call: " + e.getMessage(), null); }
    }
}
