package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Windows services through the Service Control Manager (sc.exe), on this computer or another one on the network.
 * Starting/stopping needs administrator rights on the computer that runs the service. For another computer the
 * runner's Windows account is used, or a user and password from the vault (the password goes through stdin, never
 * the command line).
 */
@OoaPackage(id = "ooa.services", name = "Windows services", version = "1.1.0", description = "Get the status of, start, stop, pause, resume and restart Windows services on this or another computer.")
public final class ServicesPackage {
    private static final Pattern STATE = Pattern.compile("STATE\\s*:\\s*\\d+\\s+(\\w+)");
    private static final Pattern FAILED = Pattern.compile("FAILED\\s+(\\d+)");
    static final String D_COMPUTER = "Computer that runs the service, e.g. FILESRV01 or 10.0.0.5; this computer when empty. Needs administrator rights there and the Remote Service Management firewall rule.";
    static final String D_USER = "Windows user (DOMAIN\\\\user) for the other computer; the runner's account when empty.";
    static final String D_PASSWORD = "Password of user, from the vault.";

    /** Where and as whom sc.exe runs. */
    record Target(String computer, String user, Object password) {
        static final Target LOCAL = new Target(null, null, null);
        boolean remote() { return computer != null; }
        String label() { return remote() ? " on " + computer : ""; }
    }

    static Target target(String computer, String user, Object password) {
        String c = computer == null ? "" : computer.trim();
        if (c.startsWith("\\\\")) c = c.substring(2);
        if (c.isEmpty() || c.equals(".") || c.equalsIgnoreCase("localhost")) {
            if (user != null && !user.isBlank()) throw new ActionException("SERVICES.BAD_INPUT", "user applies only to another computer", "Fill in computer, or clear user and password.");
            return Target.LOCAL;
        }
        if (!c.matches("^[A-Za-z0-9_.\\-:\\[\\]]{1,255}$"))
            throw new ActionException("SERVICES.BAD_COMPUTER", "Invalid computer name '" + computer + "'", "Use a computer name, a fully qualified name or an IP address, without spaces.");
        String u = user == null || user.isBlank() ? null : user.trim();
        if (u != null && password == null) throw new ActionException("SERVICES.NO_SECRET", "A password is needed with user " + u, "Choose the password from the vault.");
        if (u != null && !(password instanceof SecureString))
            throw new ActionException("SERVICES.NO_SECRET", "The password must come from the vault", "Choose a credential from the vault instead of typing the password.");
        return new Target(c, u, u == null ? null : password);
    }

    private static void windows() {
        if (!ProcessRunner.WINDOWS) throw new ActionException("SYSTEM.WINDOWS_ONLY", "Windows services exist only on Windows", "Run this agent on a Windows runner.");
    }

    private static void checkName(String name) {
        if (name == null || !name.matches("^[A-Za-z0-9_.\\- $]{1,256}$")) throw new ActionException("SERVICES.BAD_NAME", "Invalid service name '" + name + "'", "Use the service name shown by sc query, e.g. Spooler.");
    }

    /** sc.exe arguments for a verb and service, with \\computer first for another computer. */
    static List<String> scCommand(Target t, String verb, String name) {
        checkName(name);
        List<String> cmd = new ArrayList<>();
        cmd.add("sc.exe");
        if (t.remote()) cmd.add("\\\\" + t.computer());
        cmd.add(verb);
        cmd.add(name);
        return cmd;
    }

    /** Signs in to the other computer's IPC$ share with the given user (WNetAddConnection2), runs sc.exe, then signs out. */
    static final String AS_USER = """
            $ErrorActionPreference = 'Stop'
            $user = [Console]::In.ReadLine(); $secret = [Console]::In.ReadLine(); $computer = [Console]::In.ReadLine()
            $scArgs = ([Console]::In.ReadLine()) -split "`t"
            Add-Type -TypeDefinition @'
            using System; using System.Runtime.InteropServices;
            public static class OoaNetUse {
              [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
              public class NETRESOURCE { public int dwScope; public int dwType; public int dwDisplayType; public int dwUsage;
                public string lpLocalName; public string lpRemoteName; public string lpComment; public string lpProvider; }
              [DllImport("mpr.dll", CharSet = CharSet.Unicode)] public static extern int WNetAddConnection2(NETRESOURCE r, string password, string user, int flags);
              [DllImport("mpr.dll", CharSet = CharSet.Unicode)] public static extern int WNetCancelConnection2(string name, int flags, bool force);
            }
            '@
            $share = "\\\\$computer\\IPC$"
            $r = New-Object OoaNetUse+NETRESOURCE
            $r.dwType = 0; $r.lpRemoteName = $share
            $rc = [OoaNetUse]::WNetAddConnection2($r, $secret, $user, 0)
            $secret = $null
            if ($rc -ne 0 -and $rc -ne 1219) { Write-Output "__OOA_NETUSE__ $rc"; exit 3 }
            try { & sc.exe @scArgs; $code = $LASTEXITCODE } finally { if ($rc -eq 0) { [void][OoaNetUse]::WNetCancelConnection2($share, 0, $true) } }
            exit $code
            """;

    static ProcessRunner.Result sc(ActionContext ctx, Target t, String verb, String name) {
        windows();
        List<String> cmd = scCommand(t, verb, name);
        if (t.user() == null) return ProcessRunner.run(ctx, cmd, null, null, 60);
        Path script = null;
        try {
            script = Files.createTempFile("ooa-sc-", ".ps1");
            Files.writeString(script, "﻿" + AS_USER, StandardCharsets.UTF_8);
            String stdin = t.user() + "\n" + ((SecureString) t.password()).reveal() + "\n" + t.computer() + "\n" + String.join("\t", cmd.subList(1, cmd.size())) + "\n";
            ProcessRunner.Result r = ProcessRunner.run(ctx, List.of("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.toString()),
                    null, stdin, 90, null, StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("__OOA_NETUSE__ (\\d+)").matcher(r.stdout());
            if (m.find()) {
                int rc = Integer.parseInt(m.group(1));
                if (rc == 1326 || rc == 86 || rc == 5) throw new ActionException("SERVICES.LOGON_FAILED", "Cannot sign in to " + t.computer() + " as " + t.user() + " (error " + rc + ")",
                        "Check the user name (DOMAIN\\user) and the password in the vault.");
                throw new ActionException("SERVICES.UNREACHABLE", "Cannot connect to " + t.computer() + " (error " + rc + ")",
                        "Check the computer name, that it is switched on and that File and Printer Sharing is allowed through its firewall.", true);
            }
            return r;
        } catch (IOException e) {
            throw new ActionException("FILE.IO_ERROR", "Cannot write the sign-in script: " + e.getMessage(), "Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it.", true);
        } finally {
            if (script != null) try { Files.deleteIfExists(script); } catch (IOException ignored) { }
        }
    }

    /** Typed error for sc.exe output, or null when sc.exe succeeded. */
    static ActionException scError(Target t, String verb, String name, ProcessRunner.Result r) {
        String out = r.stdout() + "\n" + r.stderr();
        Matcher m = FAILED.matcher(out);
        int code = m.find() ? Integer.parseInt(m.group(1)) : (r.exitCode() == 0 ? 0 : r.exitCode());
        if (code == 0) return null;
        return switch (code) {
            case 5 -> new ActionException("SERVICES.ACCESS_DENIED", "Access denied to " + verb + " '" + name + "'" + t.label(),
                    t.remote() ? "Use an account that is an administrator on " + t.computer() + " (user and password inputs)." : "Run the agent with administrator rights.");
            case 1060 -> new ActionException("SERVICES.NOT_FOUND", "Service '" + name + "' does not exist" + t.label(), "Check the service name (not the display name).");
            case 1722, 1726, 53, 1707, 1717, 1753, 6 -> new ActionException("SERVICES.UNREACHABLE", "Cannot reach the Service Control Manager on " + (t.remote() ? t.computer() : "this computer") + " (error " + code + ")",
                    "Check the computer name, that it is on the network and that the Remote Service Management firewall rule is enabled.", true);
            case 1056, 1062 -> null;  // already running / not started: fine for start and stop
            case 1052 -> new ActionException("SERVICES.CANNOT_PAUSE", "'" + name + "' does not support pause and resume", "Use Stop service and Start service instead.");
            case 1058 -> new ActionException("SERVICES.DISABLED", "'" + name + "' is disabled" + t.label(), "Set its startup type to Manual or Automatic first.");
            case 1053 -> new ActionException("SERVICES.NOT_RESPONDING", "'" + name + "' did not respond to the request in time" + t.label(), "Check the service's own logs.", true);
            default -> new ActionException("SERVICES.FAILED", "sc " + verb + " " + name + t.label() + " failed: " + ApplicationPackage.tail(out.strip()), "Run the runner with an account that may manage Windows services, and check the service's name.");
        };
    }

    static String status(ActionContext ctx, String name) { return status(ctx, Target.LOCAL, name); }

    static String status(ActionContext ctx, Target t, String name) {
        checkName(name);
        ProcessRunner.Result r = sc(ctx, t, "query", name);
        ActionException e = scError(t, "query", name, r);
        if (e != null) throw e;
        Matcher m = STATE.matcher(r.stdout());
        return m.find() ? m.group(1).toUpperCase(Locale.ROOT) : "UNKNOWN";
    }

    private static void control(ActionContext ctx, Target t, String verb, String name, String wanted, int timeoutSeconds) {
        checkName(name);
        ProcessRunner.Result r = sc(ctx, t, verb, name);
        ActionException e = scError(t, verb, name, r);
        if (e != null) throw e;
        waitFor(ctx, t, name, wanted, timeoutSeconds);
    }

    private static void waitFor(ActionContext ctx, Target t, String name, String wanted, int timeoutSeconds) {
        if (timeoutSeconds < 0) return;  // asked not to wait
        long end = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (!status(ctx, t, name).equals(wanted)) {
            if (System.currentTimeMillis() > end) throw new ActionException("SERVICES.TIMEOUT", "'" + name + "'" + t.label() + " did not reach " + wanted + " within " + timeoutSeconds + " s", "Raise the timeout, or check the service's own log for why it does not start or stop.", true);
            try { Thread.sleep(t.remote() ? 1000 : 500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            ctx.checkCancelled();
        }
    }

    // ------------------------------------------------------------------ actions

    public Map<String, Object> getStatus(ActionContext ctx, String name) { return getStatus(ctx, name, null, null, null); }

    @Action(id = "get_status", name = "Get service status", category = "Services", idempotent = true, description = "Return RUNNING, STOPPED, PAUSED, START_PENDING or STOP_PENDING.",
            panel = "name, [Service on another computer: computer, user, password]")
    @Output(name = "status", type = OoaType.STRING, description = "Service state.")
    public Map<String, Object> getStatus(ActionContext ctx,
            @Input(name = "name", type = OoaType.STRING, example = "Spooler", description = "Service name.") String name,
            @Input(name = "computer", type = OoaType.STRING, required = false, example = "FILESRV01", description = D_COMPUTER) String computer,
            @Input(name = "user", type = OoaType.STRING, required = false, visibleWhen = "computer!=", description = D_USER) String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, visibleWhen = "computer!=", description = D_PASSWORD) Object password) {
        Target t = target(computer, user, password);
        return Map.of("status", status(ctx, t, name));
    }

    public Map<String, Object> start(ActionContext ctx, String name, int t, boolean wait) { return start(ctx, name, t, wait, null, null, null); }

    @Action(id = "start", name = "Start service", category = "Services", timeoutSeconds = 180, description = "Start a service and wait until it is running.",
            panel = "name, [Service on another computer: computer, user, password] | wait, timeout_seconds")
    public Map<String, Object> start(ActionContext ctx,
            @Input(name = "name", type = OoaType.STRING, description = "Service name.") String name,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "60", visibleWhen = "wait=true", min = 1, description = "How long to wait.") int t,
            @Input(name = "wait", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Wait until the service reaches the new state; false continues as soon as the request is sent.") boolean wait,
            @Input(name = "computer", type = OoaType.STRING, required = false, example = "FILESRV01", description = D_COMPUTER) String computer,
            @Input(name = "user", type = OoaType.STRING, required = false, visibleWhen = "computer!=", description = D_USER) String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, visibleWhen = "computer!=", description = D_PASSWORD) Object password) {
        control(ctx, target(computer, user, password), "start", name, "RUNNING", wait ? t : -1); return Map.of();
    }

    public Map<String, Object> stop(ActionContext ctx, String name, int t, boolean wait) { return stop(ctx, name, t, wait, null, null, null); }

    @Action(id = "stop", name = "Stop service", category = "Services", timeoutSeconds = 180, description = "Stop a service and wait until it has stopped.",
            panel = "name, [Service on another computer: computer, user, password] | wait, timeout_seconds")
    public Map<String, Object> stop(ActionContext ctx,
            @Input(name = "name", type = OoaType.STRING, description = "Service name.") String name,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "60", visibleWhen = "wait=true", min = 1, description = "How long to wait.") int t,
            @Input(name = "wait", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Wait until the service reaches the new state; false continues as soon as the request is sent.") boolean wait,
            @Input(name = "computer", type = OoaType.STRING, required = false, example = "FILESRV01", description = D_COMPUTER) String computer,
            @Input(name = "user", type = OoaType.STRING, required = false, visibleWhen = "computer!=", description = D_USER) String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, visibleWhen = "computer!=", description = D_PASSWORD) Object password) {
        control(ctx, target(computer, user, password), "stop", name, "STOPPED", wait ? t : -1); return Map.of();
    }

    public Map<String, Object> restart(ActionContext ctx, String name, int t) { return restart(ctx, name, t, null, null, null); }

    @Action(id = "restart", name = "Restart service", category = "Services", timeoutSeconds = 360, description = "Stop then start a service.",
            panel = "name, [Service on another computer: computer, user, password] | timeout_seconds")
    public Map<String, Object> restart(ActionContext ctx,
            @Input(name = "name", type = OoaType.STRING, description = "Service name.") String name,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "60", min = 1, description = "How long to wait for each phase.") int t,
            @Input(name = "computer", type = OoaType.STRING, required = false, example = "FILESRV01", description = D_COMPUTER) String computer,
            @Input(name = "user", type = OoaType.STRING, required = false, visibleWhen = "computer!=", description = D_USER) String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, visibleWhen = "computer!=", description = D_PASSWORD) Object password) {
        Target tg = target(computer, user, password);
        if (!status(ctx, tg, name).equals("STOPPED")) control(ctx, tg, "stop", name, "STOPPED", t);
        control(ctx, tg, "start", name, "RUNNING", t);
        return Map.of();
    }

    /** Command used to pause (sc pause) or resume (sc continue) a service. */
    static List<String> pauseCommand(String name, boolean pause) { return scCommand(Target.LOCAL, pause ? "pause" : "continue", name); }

    private static void pauseOrResume(ActionContext ctx, Target t, String name, boolean pause, int timeoutSeconds) {
        String state = status(ctx, t, name);  // also checks Windows, the name and that the service exists
        String wanted = pause ? "PAUSED" : "RUNNING";
        if (state.equals(wanted)) return;
        if (state.equals("STOPPED"))
            throw new ActionException("SERVICES.NOT_RUNNING", "'" + name + "'" + t.label() + " is stopped, so it cannot be " + (pause ? "paused" : "resumed"), "Use Start service instead.");
        String verb = pause ? "pause" : "continue";
        ActionException e = scError(t, verb, name, sc(ctx, t, verb, name));
        if (e != null) throw e;
        waitFor(ctx, t, name, wanted, timeoutSeconds);
    }

    public Map<String, Object> pause(ActionContext ctx, String name, int t, boolean wait) { return pause(ctx, name, t, wait, null, null, null); }

    @Action(id = "pause", name = "Pause service", category = "Services", timeoutSeconds = 180,
            description = "Pause a running service that supports pausing and wait until it is paused.",
            panel = "name, [Service on another computer: computer, user, password] | wait, timeout_seconds")
    public Map<String, Object> pause(ActionContext ctx,
            @Input(name = "name", type = OoaType.STRING, description = "Service name.") String name,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "60", visibleWhen = "wait=true", min = 1, description = "How long to wait.") int t,
            @Input(name = "wait", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Wait until the service reaches the new state; false continues as soon as the request is sent.") boolean wait,
            @Input(name = "computer", type = OoaType.STRING, required = false, example = "FILESRV01", description = D_COMPUTER) String computer,
            @Input(name = "user", type = OoaType.STRING, required = false, visibleWhen = "computer!=", description = D_USER) String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, visibleWhen = "computer!=", description = D_PASSWORD) Object password) {
        pauseOrResume(ctx, target(computer, user, password), name, true, wait ? t : -1); return Map.of();
    }

    public Map<String, Object> resume(ActionContext ctx, String name, int t, boolean wait) { return resume(ctx, name, t, wait, null, null, null); }

    @Action(id = "resume", name = "Resume service", category = "Services", timeoutSeconds = 180,
            description = "Continue a paused service and wait until it is running again.",
            panel = "name, [Service on another computer: computer, user, password] | wait, timeout_seconds")
    public Map<String, Object> resume(ActionContext ctx,
            @Input(name = "name", type = OoaType.STRING, description = "Service name.") String name,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "60", visibleWhen = "wait=true", min = 1, description = "How long to wait.") int t,
            @Input(name = "wait", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Wait until the service reaches the new state; false continues as soon as the request is sent.") boolean wait,
            @Input(name = "computer", type = OoaType.STRING, required = false, example = "FILESRV01", description = D_COMPUTER) String computer,
            @Input(name = "user", type = OoaType.STRING, required = false, visibleWhen = "computer!=", description = D_USER) String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, visibleWhen = "computer!=", description = D_PASSWORD) Object password) {
        pauseOrResume(ctx, target(computer, user, password), name, false, wait ? t : -1); return Map.of();
    }

    public boolean isRunning(ActionContext ctx, String name) { return isRunning(ctx, name, null, null, null); }

    @Condition(id = "is_running", name = "Service is running", description = "True when the service state is RUNNING.")
    public boolean isRunning(ActionContext ctx,
            @Input(name = "name", type = OoaType.STRING, description = "Service name.") String name,
            @Input(name = "computer", type = OoaType.STRING, required = false, example = "FILESRV01", description = D_COMPUTER) String computer,
            @Input(name = "user", type = OoaType.STRING, required = false, visibleWhen = "computer!=", description = D_USER) String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, visibleWhen = "computer!=", description = D_PASSWORD) Object password) {
        return status(ctx, target(computer, user, password), name).equals("RUNNING");
    }
}
