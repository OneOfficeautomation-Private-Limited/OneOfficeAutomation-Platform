package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.SecureString;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Windows sign-in for HTTP (intranet sites, SharePoint on premises, IIS and Exchange web services), which Java's
 * HttpClient does not do:
 * <ul>
 *   <li>{@code user_password}: NTLM (or Kerberos when the runner is set up for it) with a user name (DOMAIN\\user) and a
 *   password from the vault, through Java's HttpURLConnection, which answers NTLM challenges on any operating system;</li>
 *   <li>{@code current_user}: the runner's own Windows account, without a password (Negotiate: Kerberos, else NTLM),
 *   through .NET's HttpClient with UseDefaultCredentials, so only on Windows runners.</li>
 * </ul>
 * Both return the same status / headers / body as the plain path.
 */
final class IntegratedAuthHttp {
    private IntegratedAuthHttp() {}

    record Response(int status, Map<String, String> headers, byte[] body, String finalUrl) {
        String text() { return new String(body, StandardCharsets.UTF_8); }
    }

    static final Set<String> MODES = Set.of("current_user", "user_password");

    static {
        // NTLM is what intranet servers that offer several schemes expect from a client with a password; without this
        // Java tries Kerberos first, which needs a krb5 set-up most runners do not have
        if (System.getProperty("http.auth.preference") == null) System.setProperty("http.auth.preference", "NTLM");
    }

    static void check(String mode) {
        if (mode != null && !mode.isBlank() && !MODES.contains(mode))
            throw new ActionException("REST.BAD_AUTH", "Unknown windows_auth '" + mode + "'", "Use current_user or user_password.");
    }

    /** Sends one request with Windows sign-in. {@code outFile} receives the body instead of memory when given. */
    static Response send(ActionContext ctx, String mode, String method, String url, Map<String, String> headers, byte[] body, String user, Object password,
                         int timeoutSeconds, boolean followRedirects, Path outFile) {
        check(mode);
        if ("current_user".equals(mode)) return currentUser(ctx, method, url, headers, body, timeoutSeconds, followRedirects, outFile);
        if (user == null || user.isBlank())
            throw new ActionException("REST.NO_USER", "windows_auth user_password needs basic_user (DOMAIN\\user) and basic_password",
                    "Fill basic_user and basic_password, or use current_user to sign in as the runner's Windows account.");
        if (!(password instanceof SecureString pw))
            throw new ActionException("REST.NO_SECRET", "Input 'basic_password' must come from the vault (a Credential), so the request was not sent",
                    "Give a vault reference {\"$credential\": \"locker/credential/attribute\"} or a Credential variable.");
        return withPassword(method, url, headers, body, user.strip(), pw, timeoutSeconds, followRedirects, outFile);
    }

    // ---------------------------------------------------------------- user and password: Java's NTLM

    static Response withPassword(String method, String url, Map<String, String> headers, byte[] body, String user, SecureString password,
                                 int timeoutSeconds, boolean followRedirects, Path outFile) {
        HttpURLConnection c;
        try { c = (HttpURLConnection) URI.create(url).toURL().openConnection(); }   // the system proxy settings, as HttpClient
        catch (IllegalArgumentException | IOException e) { throw new ActionException("REST.BAD_URL", "Invalid URL: " + url, "Check the address, e.g. https://intranet.example.com/api."); }
        char[] pw = password.reveal().toCharArray();
        c.setAuthenticator(new Authenticator() {
            @Override protected PasswordAuthentication getPasswordAuthentication() {
                return getRequestorType() == RequestorType.SERVER ? new PasswordAuthentication(user, pw) : null;
            }
        });
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(20_000);
            c.setReadTimeout(Math.max(1, timeoutSeconds) * 1000);
            c.setInstanceFollowRedirects(followRedirects);
            c.setUseCaches(false);
            if (headers != null) headers.forEach(c::setRequestProperty);
            if (body != null && body.length > 0) {
                // not streamed: the body is buffered so it can be sent again after the sign-in challenge
                c.setDoOutput(true);
                try (OutputStream o = c.getOutputStream()) { o.write(body); }
            }
            int status = c.getResponseCode();
            Map<String, String> h = new LinkedHashMap<>();
            c.getHeaderFields().forEach((k, v) -> { if (k != null) h.put(k.toLowerCase(Locale.ROOT), String.join(", ", v)); });
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] data = new byte[0];
            if (in != null) {
                try (InputStream i = in) {
                    if (outFile != null && status < 400) { Files.copy(i, outFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
                    else data = i.readAllBytes();
                }
            }
            return new Response(status, h, data, c.getURL().toString());
        } catch (SocketTimeoutException e) {
            throw new ActionException("REST.TIMEOUT", "No response from " + url + " within " + timeoutSeconds + " s", "Raise timeout_seconds or check the service.", true);
        } catch (IOException e) {
            throw new ActionException("REST.UNREACHABLE", "Cannot reach " + url + ": " + NetErrors.reason(e, null), "Check the URL, the user name (DOMAIN\\user) and network access.", true);
        } finally {
            Arrays.fill(pw, '\0');
            c.disconnect();
        }
    }

    // ---------------------------------------------------------------- current Windows user: .NET HttpClient

    static final String SCRIPT = """
            $ErrorActionPreference = 'Stop'
            try {
              $req = [Console]::In.ReadToEnd() | ConvertFrom-Json
              Add-Type -AssemblyName System.Net.Http
              [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
              try { [Net.WebRequest]::DefaultWebProxy.Credentials = [Net.CredentialCache]::DefaultNetworkCredentials } catch { }
              $h = New-Object System.Net.Http.HttpClientHandler
              $h.UseDefaultCredentials = $true
              $h.PreAuthenticate = $true
              $h.AllowAutoRedirect = [bool]$req.redirects
              $c = New-Object System.Net.Http.HttpClient($h)
              $c.Timeout = [TimeSpan]::FromSeconds([double]$req.timeout)
              $m = New-Object System.Net.Http.HttpRequestMessage((New-Object System.Net.Http.HttpMethod($req.method)), [Uri]$req.url)
              $bytes = if ($req.body) { [Convert]::FromBase64String($req.body) } else { $null }
              if ($bytes -ne $null) { $m.Content = New-Object System.Net.Http.ByteArrayContent -ArgumentList (,$bytes) }
              if ($req.headers) {
                foreach ($p in $req.headers.PSObject.Properties) {
                  if ($p.Name -like 'Content-*') {
                    if ($m.Content -eq $null) { $m.Content = New-Object System.Net.Http.ByteArrayContent -ArgumentList (,[byte[]]@()) }
                    $null = $m.Content.Headers.Remove($p.Name); $null = $m.Content.Headers.TryAddWithoutValidation($p.Name, [string]$p.Value)
                  } else { $null = $m.Headers.TryAddWithoutValidation($p.Name, [string]$p.Value) }
                }
              }
              $r = $c.SendAsync($m).GetAwaiter().GetResult()
              $hd = @{}
              foreach ($x in $r.Headers) { $hd[$x.Key.ToLower()] = ($x.Value -join ', ') }
              if ($r.Content -ne $null) { foreach ($x in $r.Content.Headers) { $hd[$x.Key.ToLower()] = ($x.Value -join ', ') } }
              $b64 = ''
              if ($req.out_file -and [int]$r.StatusCode -lt 400) {
                $fs = [IO.File]::Create($req.out_file)
                try { $r.Content.CopyToAsync($fs).GetAwaiter().GetResult() } finally { $fs.Dispose() }
              } elseif ($r.Content -ne $null) { $b64 = [Convert]::ToBase64String($r.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()) }
              $out = @{ status = [int]$r.StatusCode; headers = $hd; body = $b64; url = $r.RequestMessage.RequestUri.AbsoluteUri }
              [Console]::Out.Write('__OOA_HTTP__' + ($out | ConvertTo-Json -Compress -Depth 4))
            } catch {
              $e = $_.Exception; while ($e.InnerException -ne $null) { $e = $e.InnerException }
              $kind = if ($e -is [System.Threading.Tasks.TaskCanceledException]) { 'timeout' } else { 'error' }
              [Console]::Out.Write('__OOA_FAIL__' + $kind + ' ' + $e.Message); exit 3
            }
            """;

    @SuppressWarnings("unchecked")
    static Response currentUser(ActionContext ctx, String method, String url, Map<String, String> headers, byte[] body, int timeoutSeconds,
                                boolean followRedirects, Path outFile) {
        if (!ProcessRunner.WINDOWS)
            throw new ActionException("REST.AUTH_UNSUPPORTED", "Signing in as the current Windows user works only on Windows runners",
                    "Use windows_auth user_password with basic_user and basic_password, or run the agent on a Windows runner.");
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("method", method); req.put("url", url); req.put("timeout", timeoutSeconds); req.put("redirects", followRedirects);
        req.put("headers", headers == null ? Map.of() : headers);
        req.put("body", body == null || body.length == 0 ? "" : Base64.getEncoder().encodeToString(body));
        if (outFile != null) req.put("out_file", outFile.toAbsolutePath().toString());
        Path script = null;
        try {
            script = Files.createTempFile("ooa-http-", ".ps1");
            Files.writeString(script, "﻿" + ScriptPackage.UTF8_PREAMBLE + "\n" + SCRIPT, StandardCharsets.UTF_8);
            ProcessRunner.Result r = ProcessRunner.run(ctx, List.of("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.toString()),
                    null, JsonLite.write(req), timeoutSeconds + 30.0, null, StandardCharsets.UTF_8);
            String out = r.stdout();
            int ok = out.indexOf("__OOA_HTTP__"), fail = out.indexOf("__OOA_FAIL__");
            if (ok < 0) {
                String why = fail >= 0 ? out.substring(fail + 12).strip() : ApplicationPackage.tail(r.stderr().isBlank() ? out : r.stderr());
                if (why.startsWith("timeout")) throw new ActionException("REST.TIMEOUT", "No response from " + url + " within " + timeoutSeconds + " s", "Raise timeout_seconds or check the service.", true);
                throw new ActionException("REST.UNREACHABLE", "Cannot reach " + url + " as the current Windows user: " + why.replaceFirst("^error ", ""),
                        "Check the URL and that the runner's Windows account may use the site.", true);
            }
            Map<String, Object> j = (Map<String, Object>) JsonLite.parse(out.substring(ok + 12).strip());
            Map<String, String> h = new LinkedHashMap<>();
            if (j.get("headers") instanceof Map<?, ?> hm) hm.forEach((k, v) -> h.put(String.valueOf(k), String.valueOf(v)));
            String b64 = j.get("body") == null ? "" : String.valueOf(j.get("body"));
            return new Response(((Number) j.get("status")).intValue(), h, b64.isEmpty() ? new byte[0] : Base64.getDecoder().decode(b64), String.valueOf(j.get("url")));
        } catch (IOException e) {
            throw new ActionException("FILE.IO_ERROR", "Cannot write the sign-in script: " + e.getMessage(), "Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it.", true);
        } finally {
            if (script != null) try { Files.deleteIfExists(script); } catch (IOException ignored) { }
        }
    }
}
