package com.oneofficeautomation.cli;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Winsvc;

import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Runs ONEOFFICEAUTOMATION as Windows services, so the Command Center (or a runner) starts with Windows, keeps running
 * when nobody is signed in, and restarts by itself if it stops.
 * <pre>
 * ooa service install [--mode server|control-room|agent] [--name NAME] [--port 8720] [--host H] [--data DIR]
 *                     [--workspace DIR] [--agent-config FILE] [--account DOMAIN\\user --password-env VAR] [--no-start]
 * ooa service uninstall [--name NAME]      ooa service status [--name NAME]      ooa service start|stop [--name NAME]
 * ooa agent install-task [--config FILE] [--workspace DIR]   the runner at sign-in, for agents that need a desktop
 * </pre>
 * Installing needs an administrator prompt. A service has no desktop (Windows session 0), so a runner service suits
 * headless agents; agents that click and type need a signed-in session (install-task) until unattended sign-in comes.
 * The service runs "ooa service-run MODE ARGS": the same program, reporting to the Windows service manager.
 */
final class WindowsService {
    private WindowsService() { }

    static final String DEFAULT_SERVER = "OOA Command Center", DEFAULT_RUNNER = "OOA Runner";

    static int run(String[] a) throws Exception {
        if (a.length == 0) { usage(); return 1; }
        Map<String, String> o = Ooa.options(Arrays.copyOfRange(a, 1, a.length));
        String mode = o.getOrDefault("mode", "server");
        String name = o.getOrDefault("name", mode.equals("agent") ? DEFAULT_RUNNER : DEFAULT_SERVER);
        return switch (a[0]) {
            case "install" -> install(name, mode, o);
            case "uninstall" -> uninstall(name);
            case "start" -> control(name, true);
            case "stop" -> control(name, false);
            case "status" -> status(name);
            default -> { usage(); yield 1; }
        };
    }

    static void usage() {
        System.err.println("usage: ooa service install [--mode server|control-room|agent] [--name NAME] [--data DIR] [--port N] [--account USER --password-env VAR] [--no-start] | uninstall | start | stop | status [--name NAME]");
    }

    private static int ok(String msg) { System.out.println(msg); return 0; }

    /** The program's own install folder (the parent of lib\), found from the class path. */
    static Path appHome() {
        for (String e : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            Path p = Path.of(e.replace("*", "x")).toAbsolutePath();
            if (p.getParent() != null && p.getParent().getFileName() != null && p.getParent().getFileName().toString().equals("lib")) return p.getParent().getParent();
        }
        throw new IllegalStateException("Run this from an installed ONEOFFICEAUTOMATION (bin\\ooa.bat), not from the build");
    }

    /** java.exe plus the class path and main class: what the service (or the sign-in task) starts. */
    static List<String> javaCommand(Path home, String... args) {
        List<String> c = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(), "-Xmx1g", "-Dfile.encoding=UTF-8", "-Djava.library.path=" + home.resolve("lib"),
            "-cp", home.resolve("lib").toString() + "\\*", Ooa.class.getName()));
        c.addAll(List.of(args));
        return c;
    }

    /** One argument of a Windows command line: quoted when it holds blanks (the paths here never hold quotes). */
    static String quote(String s) { return s.isEmpty() || s.matches(".*\\s.*") ? "\"" + s + "\"" : s; }

    static String commandLine(List<String> c) { return String.join(" ", c.stream().map(WindowsService::quote).toList()); }

    /**
     * The arguments the service runs with. Paths that would otherwise come from the installing user's profile are
     * written out in full, because a service account has a different profile.
     */
    static List<String> serviceArgs(String mode, Map<String, String> o) {
        List<String> r = new ArrayList<>(List.of("service-run", mode));
        String home = System.getProperty("user.home");
        switch (mode) {
            case "server", "control-room" -> {
                r.addAll(List.of("--data", Ooa.dataDir(o).toAbsolutePath().toString(), "--port", o.getOrDefault("port", "8720")));
                if (o.containsKey("host")) r.addAll(List.of("--host", o.get("host")));
                if (mode.equals("server")) {
                    r.addAll(List.of("--workspace", o.getOrDefault("workspace", Path.of(home, "OOA Workspace").toString()), "--no-browser", "--no-agent"));
                }
            }
            case "agent" -> r.addAll(List.of("start", "--config", o.getOrDefault("agent-config", com.oneofficeautomation.agent.Agent.defaultConfig().toString()),
                "--workspace", o.getOrDefault("workspace", Path.of(home, "OOA Agent Workspace").toString())));
            default -> throw new IllegalArgumentException("--mode is server, control-room or agent");
        }
        return r;
    }

    private static final int SC_MANAGER_CONNECT = 0x0001, SC_MANAGER_CREATE_SERVICE = 0x0002, SERVICE_ALL_ACCESS = 0xF01FF,
        SERVICE_QUERY_STATUS = 0x0004, SERVICE_START = 0x0010, SERVICE_STOP = 0x0020, DELETE = 0x10000,
        SERVICE_WIN32_OWN_PROCESS = 0x10, SERVICE_AUTO_START = 2, SERVICE_ERROR_NORMAL = 1, SERVICE_CONFIG_DESCRIPTION = 1, SC_ACTION_RESTART = 1;

    private static String why(String what) {
        int e = com.sun.jna.platform.win32.Kernel32.INSTANCE.GetLastError();
        return switch (e) {
            case 5 -> what + ": access denied. Open the command prompt with 'Run as administrator' and run it again.";
            case 1060 -> what + ": no such service.";
            case 1073 -> what + ": the service exists already (ooa service uninstall first, or choose another --name).";
            default -> what + " failed (Windows error " + e + ").";
        };
    }

    static int install(String name, String mode, Map<String, String> o) throws Exception {
        Path home = appHome();
        String bin = commandLine(javaCommand(home, serviceArgs(mode, o).toArray(String[]::new)));
        String account = o.get("account"), pw = null;
        if (account != null) {
            pw = o.containsKey("password-env") ? System.getenv(o.get("password-env")) : null;
            // a group managed service account (DOMAIN\name$) has no password: Windows manages it
            if (pw == null && !account.endsWith("$")) { System.err.println("Give --password-env VAR with the account's password in that environment variable (a gMSA, DOMAIN\\name$, needs none)."); return 1; }
        }
        Advapi32 api = Advapi32.INSTANCE;
        Winsvc.SC_HANDLE scm = api.OpenSCManager(null, null, SC_MANAGER_CONNECT | SC_MANAGER_CREATE_SERVICE);
        if (scm == null) { System.err.println(why("Opening the service manager")); return 1; }
        try {
            Winsvc.SC_HANDLE svc = api.CreateService(scm, name, "ONEOFFICEAUTOMATION " + name.replaceFirst("^OOA ", ""), SERVICE_ALL_ACCESS,
                SERVICE_WIN32_OWN_PROCESS, SERVICE_AUTO_START, SERVICE_ERROR_NORMAL, bin, null, null, null, account, pw);
            if (svc == null) { System.err.println(why("Creating the service '" + name + "'")); return 1; }
            try (ServiceRef s = new ServiceRef(svc)) {
                Winsvc.SERVICE_DESCRIPTION d = new Winsvc.SERVICE_DESCRIPTION();
                d.lpDescription = mode.equals("agent")
                    ? "Runs ONEOFFICEAUTOMATION agents sent by the Command Center (headless agents; desktop agents need a signed-in runner)."
                    : "ONEOFFICEAUTOMATION Command Center" + (mode.equals("server") ? " and Agent Studio" : "") + " on port " + o.getOrDefault("port", "8720") + ".";
                api.ChangeServiceConfig2(svc, SERVICE_CONFIG_DESCRIPTION, d);
                // restart one minute after an unexpected stop; the failure count resets after a day
                List<Winsvc.SC_ACTION> actions = new ArrayList<>();
                for (int i = 0; i < 3; i++) { Winsvc.SC_ACTION ac = new Winsvc.SC_ACTION(); ac.type = SC_ACTION_RESTART; ac.delay = 60000; actions.add(ac); }
                s.service().setFailureActions(actions, 86400, null, null);
                System.out.println("Installed the service '" + name + "': it starts with Windows and restarts when it stops.");
                System.out.println("  Runs: " + bin);
                if (!o.containsKey("no-start")) { s.service().startService(); System.out.println("Started '" + name + "'."); }
            }
        } finally { api.CloseServiceHandle(scm); }
        return 0;
    }

    /** Closes a service handle opened here. */
    private record ServiceRef(com.sun.jna.platform.win32.W32Service service) implements AutoCloseable {
        ServiceRef(Winsvc.SC_HANDLE h) { this(new com.sun.jna.platform.win32.W32Service(h)); }
        @Override public void close() { service.close(); }
    }

    private static Winsvc.SC_HANDLE open(String name, int access, String what) {
        Advapi32 api = Advapi32.INSTANCE;
        Winsvc.SC_HANDLE scm = api.OpenSCManager(null, null, SC_MANAGER_CONNECT);
        if (scm == null) { System.err.println(why(what)); return null; }
        try {
            Winsvc.SC_HANDLE svc = api.OpenService(scm, name, access);
            if (svc == null) System.err.println(why(what + " '" + name + "'"));
            return svc;
        } finally { api.CloseServiceHandle(scm); }
    }

    static int uninstall(String name) {
        Winsvc.SC_HANDLE svc = open(name, SERVICE_STOP | SERVICE_QUERY_STATUS | DELETE, "Removing the service");
        if (svc == null) return 1;
        try (ServiceRef s = new ServiceRef(svc)) {
            if (s.service().queryStatus().dwCurrentState != Winsvc.SERVICE_STOPPED) s.service().stopService(30000);
            if (!Advapi32.INSTANCE.DeleteService(svc)) { System.err.println(why("Removing the service '" + name + "'")); return 1; }
        }
        return ok("Removed the service '" + name + "'.");
    }

    static int control(String name, boolean start) {
        Winsvc.SC_HANDLE svc = open(name, (start ? SERVICE_START : SERVICE_STOP) | SERVICE_QUERY_STATUS, start ? "Starting the service" : "Stopping the service");
        if (svc == null) return 1;
        try (ServiceRef s = new ServiceRef(svc)) {
            if (start) s.service().startService(); else s.service().stopService(30000);
        }
        return ok((start ? "Started '" : "Stopped '") + name + "'.");
    }

    static int status(String name) {
        Winsvc.SC_HANDLE svc = open(name, SERVICE_QUERY_STATUS, "Reading the service");
        if (svc == null) return 1;
        try (ServiceRef s = new ServiceRef(svc)) {
            int st = s.service().queryStatus().dwCurrentState;
            String text = switch (st) { case 1 -> "stopped"; case 2 -> "starting"; case 3 -> "stopping"; case 4 -> "running"; default -> "in state " + st; };
            return ok("'" + name + "' is " + text + ".");
        }
    }

    private static int exec(List<String> cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        int rc = p.waitFor();
        if (rc != 0) System.out.print(out);
        return rc;
    }

    // ---------- inside the service: report to the Windows service manager ----------

    private static Winsvc.SERVICE_STATUS_HANDLE handle;
    private static final Winsvc.SERVICE_STATUS status = new Winsvc.SERVICE_STATUS();

    private static void report(int state, int waitHintMs) {
        status.dwServiceType = 0x10;   // SERVICE_WIN32_OWN_PROCESS
        status.dwCurrentState = state;
        status.dwControlsAccepted = state == Winsvc.SERVICE_RUNNING ? Winsvc.SERVICE_ACCEPT_STOP | Winsvc.SERVICE_ACCEPT_SHUTDOWN : 0;
        status.dwWin32ExitCode = 0;
        status.dwWaitHint = waitHintMs;
        status.dwCheckPoint = state == Winsvc.SERVICE_RUNNING || state == Winsvc.SERVICE_STOPPED ? 0 : status.dwCheckPoint + 1;
        Advapi32.INSTANCE.SetServiceStatus(handle, status);
    }

    /**
     * "ooa service-run MODE ARGS": started by the service manager. Connects to it, starts the Command Center or the
     * runner in this process, and stops cleanly on a stop or shut-down request. Output goes to a log file.
     */
    static int serviceRun(String[] a) throws Exception {
        String mode = a[0];
        String[] args = Arrays.copyOfRange(a, 1, a.length);
        Map<String, String> o = Ooa.options(args);
        Path logDir = mode.equals("agent") ? Path.of(o.getOrDefault("workspace", ".")).resolve("logs") : Ooa.dataDir(o).resolve("logs");
        Files.createDirectories(logDir);
        PrintStream log = new PrintStream(new FileOutputStream(logDir.resolve("service-" + mode + ".log").toFile(), true), true, "UTF-8");
        System.setOut(log);
        System.setErr(log);
        System.out.println("---- " + LocalDateTime.now() + " service starting: " + mode);
        System.setProperty("ooa.runner.service", "1");   // a runner service that updates itself is started again by Windows
        Winsvc.SERVICE_MAIN_FUNCTION main = (argc, argv) -> {
            handle = Advapi32.INSTANCE.RegisterServiceCtrlHandlerEx("", (control, eventType, eventData, context) -> {
                if (control == Winsvc.SERVICE_CONTROL_STOP || control == Winsvc.SERVICE_CONTROL_SHUTDOWN) {
                    report(Winsvc.SERVICE_STOP_PENDING, 10000);
                    new Thread(() -> {
                        System.out.println("---- " + LocalDateTime.now() + " service stopping");
                        report(Winsvc.SERVICE_STOPPED, 0);
                        System.exit(0);
                    }, "ooa-service-stop").start();
                }
                return 0;   // NO_ERROR
            }, null);
            report(Winsvc.SERVICE_START_PENDING, 30000);
            Thread worker = new Thread(() -> {
                try {
                    switch (mode) {
                        case "server" -> Ooa.server(args);
                        case "control-room" -> Ooa.controlRoom(args);
                        case "agent" -> Ooa.agent(args);
                        default -> throw new IllegalArgumentException("Unknown mode " + mode);
                    }
                } catch (Throwable t) {
                    t.printStackTrace();
                    status.dwWin32ExitCode = 1064;   // ERROR_EXCEPTION_IN_SERVICE: the manager restarts it
                    report(Winsvc.SERVICE_STOPPED, 0);
                    System.exit(1);
                }
            }, "ooa-service-" + mode);
            worker.start();
            report(Winsvc.SERVICE_RUNNING, 0);
        };
        Winsvc.SERVICE_TABLE_ENTRY entry = new Winsvc.SERVICE_TABLE_ENTRY();
        entry.lpServiceName = "";
        entry.lpServiceProc = main;
        Winsvc.SERVICE_TABLE_ENTRY[] table = (Winsvc.SERVICE_TABLE_ENTRY[]) entry.toArray(2);
        // blocks until the service stops; fails at once when not started by the service manager
        if (!Advapi32.INSTANCE.StartServiceCtrlDispatcher(table)) {
            System.out.println("Not started by the Windows service manager: use 'ooa service install', or run 'ooa " + mode + "' directly.");
            return 1;
        }
        return 0;
    }

    // ---------- the runner at sign-in, for desktop agents ----------

    /** Registers a scheduled task that starts the runner when the current user signs in and restarts it when it stops. */
    static int installTask(Map<String, String> o) throws Exception {
        Path home = appHome();
        String config = o.getOrDefault("config", com.oneofficeautomation.agent.Agent.defaultConfig().toString());
        String ws = o.getOrDefault("workspace", Path.of(System.getProperty("user.home"), "OOA Agent Workspace").toString());
        if (!Files.exists(Path.of(config))) { System.err.println("Register the runner first: ooa agent register --url ... --key ..."); return 1; }
        List<String> cmd = new ArrayList<>(javaCommand(home, "agent", "start", "--config", config, "--workspace", ws));
        cmd.add(1, "-Dooa.runner.task=OOA Runner");   // after an update the runner starts this task again
        String java = cmd.get(0).replace("java.exe", "javaw.exe");   // no console window at sign-in
        String args = commandLine(cmd.subList(1, cmd.size()));
        String ps = "$a = New-ScheduledTaskAction -Execute '" + java.replace("'", "''") + "' -Argument '" + args.replace("'", "''") + "' -WorkingDirectory '" + home.toString().replace("'", "''") + "';"
            + "$t = New-ScheduledTaskTrigger -AtLogOn -User \"$env:USERDOMAIN\\$env:USERNAME\";"
            + "$s = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew;"
            + "Register-ScheduledTask -TaskName 'OOA Runner' -Action $a -Trigger $t -Settings $s -Force -Description 'ONEOFFICEAUTOMATION runner: runs agents from the Command Center in this signed-in session.' | Out-Null;"
            + "Start-ScheduledTask -TaskName 'OOA Runner'";
        int rc = exec(List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps));
        if (rc == 0) System.out.println("The runner starts when you sign in (task 'OOA Runner') and is starting now. Remove it with: ooa agent uninstall-task");
        return rc;
    }

    static int uninstallTask() throws Exception {
        return exec(List.of("powershell.exe", "-NoProfile", "-Command", "Unregister-ScheduledTask -TaskName 'OOA Runner' -Confirm:$false")) == 0 ? ok("Removed the task 'OOA Runner'.") : 1;
    }
}
