package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.math.BigDecimal;
import java.net.InetAddress;
import java.util.*;

/** System information and power actions. Power actions require confirm = true so they can never run by accident. */
@OoaPackage(id = "ooa.system", name = "System", version = "1.0.0", description = "Read environment variables and runner information; lock, sign out, restart or shut down the runner.")
public final class SystemPackage {

    @Action(id = "get_environment_variable", name = "Get environment variable", category = "System", idempotent = true,
            description = "Read an environment variable of the runner; variables that look like secrets are refused.",
            panel = "name, [Use a fallback value: default]")
    @Output(name = "value", type = OoaType.STRING, description = "Value, or the default when not set.")
    public Map<String, Object> getEnv(ActionContext ctx,
            @Input(name = "name", type = OoaType.STRING, description = "Variable name, e.g. COMPUTERNAME.") String name,
            @Input(name = "default", type = OoaType.STRING, required = false, description = "Value when the variable is not set.") String def) {
        if (name.toUpperCase(Locale.ROOT).matches(".*(PASSWORD|SECRET|TOKEN|API_KEY|PRIVATE).*"))
            throw new ActionException("SYSTEM.SECRET_VARIABLE", "Reading '" + name + "' is blocked because it looks like a secret",
                    "Store secrets in the credential vault and use a Credential variable.");
        // a value set earlier in this run (Set environment variable, process scope) wins; empty means removed
        if (ctx.sessions() != null && ctx.sessions().get(ProcessRunner.RUN_ENV_SESSION) instanceof Map<?, ?> runEnv && runEnv.containsKey(name)) {
            String set = String.valueOf(runEnv.get(name));
            return Out.of("value", set.isEmpty() ? def : set);
        }
        String v = System.getenv(name);
        return Out.of("value", v != null ? v : def);
    }

    static boolean secretName(String name) {
        return name.toUpperCase(Locale.ROOT).matches(".*(PASSWORD|PASSWD|SECRET|TOKEN|API_KEY|APIKEY|PRIVATE|CREDENTIAL).*");
    }

    @Action(id = "get_environment_variables", name = "Get all environment variables", category = "System", idempotent = true,
            description = "Return every environment variable of the runner as a dictionary; variables that look like secrets are left out.")
    @Output(name = "variables", type = OoaType.DICTIONARY, description = "Variable name to value, sorted by name.")
    @Output(name = "hidden_count", type = OoaType.NUMBER, description = "How many secret-looking variables were left out.")
    @SuppressWarnings("unchecked")
    public Map<String, Object> getEnvAll(ActionContext ctx) {
        Map<String, Object> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        int hidden = 0;
        Map<String, String> all = new HashMap<>(System.getenv());
        if (ctx.sessions() != null && ctx.sessions().get(ProcessRunner.RUN_ENV_SESSION) instanceof Map<?, ?> m)
            ((Map<String, String>) m).forEach((k, v) -> { if (v == null || v.isEmpty()) all.remove(k); else all.put(k, v); });
        for (var e : all.entrySet()) {
            if (secretName(e.getKey())) { hidden++; continue; }
            out.put(e.getKey(), ctx.redact(e.getValue()));
        }
        return Out.of("variables", new LinkedHashMap<>(out), "hidden_count", BigDecimal.valueOf(hidden));
    }

    /** PowerShell that stores a user-level variable; name and value arrive through environment variables. */
    static final String SET_USER_ENV_SCRIPT = "[Environment]::SetEnvironmentVariable($env:OOA_ENV_NAME, $env:OOA_ENV_VALUE, 'User')";

    @Action(id = "set_environment_variable", name = "Set environment variable", category = "System",
            description = "Set an environment variable for programs this run starts (process scope) or permanently for the runner's user (user scope, Windows).",
            panel = "name, value, scope")
    @SuppressWarnings("unchecked")
    public Map<String, Object> setEnv(ActionContext ctx,
            @Input(name = "name", type = OoaType.STRING, example = "REPORT_FOLDER", description = "Variable name: letters, digits and underscores.") String name,
            @Input(name = "value", type = OoaType.STRING, required = false, description = "Value; empty removes the variable.") String value,
            @Input(name = "scope", type = OoaType.STRING, required = false, defaultValue = "\"process\"", enumValues = {"process", "user"},
                    description = "process: programs and scripts started later in this run; user: saved for the runner's Windows user.") String scope) {
        if (name == null || !name.matches("^[A-Za-z_][A-Za-z0-9_.]{0,254}$"))
            throw new ActionException("SYSTEM.BAD_VARIABLE_NAME", "Invalid environment variable name '" + name + "'", "Use letters, digits and underscores, e.g. REPORT_FOLDER.");
        if (secretName(name))
            throw new ActionException("SYSTEM.SECRET_VARIABLE", "Setting '" + name + "' is blocked because it looks like a secret",
                    "Pass secrets to actions as Credential variables instead of environment variables.");
        String v = value == null ? "" : value;
        if ("user".equals(scope)) {
            if (!ProcessRunner.WINDOWS) throw new ActionException("SYSTEM.WINDOWS_ONLY", "User-scope variables are supported on Windows only", "Use scope process, or set the variable in the runner's shell profile.");
            Map<String, String> env = new LinkedHashMap<>();
            env.put("OOA_ENV_NAME", name);
            env.put("OOA_ENV_VALUE", v);
            ProcessRunner.Result r = ProcessRunner.run(ctx, ScriptPackage.powershellCommand(v.isEmpty()
                    ? "[Environment]::SetEnvironmentVariable($env:OOA_ENV_NAME, $null, 'User')" : SET_USER_ENV_SCRIPT), null, null, 60, env, java.nio.charset.StandardCharsets.UTF_8);
            if (r.exitCode() != 0) throw new ActionException("SYSTEM.SET_VARIABLE_FAILED", "Cannot set " + name + ": " + ApplicationPackage.tail(r.stderr() + r.stdout()), "Check that the runner's account may change this environment variable (machine variables need an administrator).");
        } else if (!"process".equals(scope)) {
            throw new ActionException("SYSTEM.BAD_SCOPE", "Unknown scope '" + scope + "'", "Use process or user.");
        }
        Map<String, String> runEnv = (Map<String, String>) ctx.sessions().computeIfAbsent(ProcessRunner.RUN_ENV_SESSION, k -> new java.util.concurrent.ConcurrentHashMap<String, String>());
        runEnv.put(name, v);  // empty means removed for programs started later
        return Map.of();
    }

    @Action(id = "get_system_info", name = "Get system info", category = "System", idempotent = true,
            description = "Return the runner name, operating system, user, processors and Java version.")
    @Output(name = "info", type = OoaType.DICTIONARY, description = "hostname, os, os_version, user, processors, java_version.")
    public Map<String, Object> info(ActionContext ctx) {
        Map<String, Object> m = new LinkedHashMap<>();
        String host;
        try { host = InetAddress.getLocalHost().getHostName(); } catch (Exception e) { host = "unknown"; }
        m.put("hostname", host);
        m.put("os", System.getProperty("os.name"));
        m.put("os_version", System.getProperty("os.version"));
        m.put("user", System.getProperty("user.name"));
        m.put("processors", BigDecimal.valueOf(Runtime.getRuntime().availableProcessors()));
        m.put("java_version", System.getProperty("java.version"));
        return Map.of("info", m);
    }

    /** shutdown command line for restart (/r, -r) or shut down (/s, -h); the message is one argument, never parsed by a shell. */
    static List<String> powerCommand(String flag, int delay, boolean force, String message, boolean windows) {
        int d = Math.max(0, Math.min(600, delay));
        List<String> cmd = new ArrayList<>();
        String m = message == null ? "" : message.replaceAll("[\\r\\n]+", " ").strip();
        if (m.length() > 512) m = m.substring(0, 512);
        if (windows) {
            cmd.addAll(List.of("shutdown.exe", flag, "/t", String.valueOf(d)));
            if (force) cmd.add("/f");
            if (!m.isEmpty()) { cmd.add("/c"); cmd.add(m); }
        } else {
            cmd.addAll(List.of("shutdown", flag, "+" + Math.max(1, d / 60)));
            if (!m.isEmpty()) cmd.add(m);
        }
        return cmd;
    }

    private static Map<String, Object> power(ActionContext ctx, boolean confirm, String what, List<String> windowsCmd, List<String> unixCmd) {
        if (!confirm) throw new ActionException("SYSTEM.CONFIRM_REQUIRED", what + " was not performed because confirm is false",
                "Set confirm to true to allow this agent to " + what.toLowerCase(Locale.ROOT) + " the runner.");
        List<String> cmd = ProcessRunner.WINDOWS ? windowsCmd : unixCmd;
        if (cmd == null) throw new ActionException("SYSTEM.WINDOWS_ONLY", what + " is supported on Windows only", "Run this agent on a Windows runner.");
        ctx.warn(what + " requested by the agent");
        ProcessRunner.Result r = ProcessRunner.run(ctx, cmd, null, null, 60);
        if (r.exitCode() != 0) throw new ActionException("SYSTEM.POWER_FAILED", what + " failed: " + ApplicationPackage.tail(r.stderr() + r.stdout()),
                "The agent may need administrator rights.");
        return Map.of();
    }

    @Action(id = "lock", name = "Lock computer", category = "Power", capabilities = "desktop", description = "Lock the screen of the current session.",
            panel = "confirm")
    public Map<String, Object> lock(ActionContext ctx, @Input(name = "confirm", type = OoaType.BOOLEAN, description = "Must be true to lock.") boolean confirm) {
        return power(ctx, confirm, "Lock", List.of("rundll32.exe", "user32.dll,LockWorkStation"), null);
    }

    @Action(id = "log_off", name = "Sign out", category = "Power", capabilities = "desktop", description = "Sign out the current Windows user (open programs are closed).",
            panel = "confirm | force")
    public Map<String, Object> logOff(ActionContext ctx, @Input(name = "confirm", type = OoaType.BOOLEAN, description = "Must be true to sign out.") boolean confirm,
            @Input(name = "force", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Close programs without asking them to save (unsaved work is lost).") boolean force) {
        return power(ctx, confirm, "Sign out", force ? List.of("shutdown.exe", "/l", "/f") : List.of("shutdown.exe", "/l"), null);
    }

    @Action(id = "restart", name = "Restart computer", category = "Power", description = "Restart the runner after a delay.",
            panel = "confirm | delay_seconds, force, message")
    public Map<String, Object> restart(ActionContext ctx,
            @Input(name = "confirm", type = OoaType.BOOLEAN, description = "Must be true to restart.") boolean confirm,
            @Input(name = "delay_seconds", type = OoaType.NUMBER, required = false, defaultValue = "30", min = 0, max = 315360000, description = "Seconds before restarting.") int delay,
            @Input(name = "force", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Close programs without asking them to save (unsaved work is lost).") boolean force,
            @Input(name = "message", type = OoaType.STRING, required = false, description = "Reason shown to signed-in users and written to the Windows event log.") String message) {
        return power(ctx, confirm, "Restart", powerCommand("/r", delay, force, message, true), powerCommand("-r", delay, force, message, false));
    }

    @Action(id = "shut_down", name = "Shut down computer", category = "Power", description = "Shut the runner down after a delay.",
            panel = "confirm | delay_seconds, force, message")
    public Map<String, Object> shutDown(ActionContext ctx,
            @Input(name = "confirm", type = OoaType.BOOLEAN, description = "Must be true to shut down.") boolean confirm,
            @Input(name = "delay_seconds", type = OoaType.NUMBER, required = false, defaultValue = "30", min = 0, max = 315360000, description = "Seconds before shutting down.") int delay,
            @Input(name = "force", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Close programs without asking them to save (unsaved work is lost).") boolean force,
            @Input(name = "message", type = OoaType.STRING, required = false, description = "Reason shown to signed-in users and written to the Windows event log.") String message) {
        return power(ctx, confirm, "Shut down", powerCommand("/s", delay, force, message, true), powerCommand("-h", delay, force, message, false));
    }

    // ---------- processes, screens, desktop, recycle bin and notifications (gap G08, 3 Nov 2026) ----------

    /** Runs a PowerShell script with its inputs as environment variables (never pasted into the script); returns stdout. */
    private static String ps(ActionContext ctx, String what, String script, Map<String, String> env, int timeoutSeconds) {
        if (!ProcessRunner.WINDOWS) throw new ActionException("SYSTEM.WINDOWS_ONLY", what + " is supported on Windows only", "Run this agent on a Windows runner.");
        // -EncodedCommand, not -Command: Windows command lines lose the script's double quotes otherwise
        String encoded = Base64.getEncoder().encodeToString((ScriptPackage.UTF8_PREAMBLE + "; " + script).getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
        List<String> cmd = List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded);
        ProcessRunner.Result r = ProcessRunner.run(ctx, cmd, null, null, timeoutSeconds, env, java.nio.charset.StandardCharsets.UTF_8);
        if (r.exitCode() != 0) throw new ActionException("SYSTEM.FAILED", what + " failed: " + ApplicationPackage.tail(r.stderr() + r.stdout()), "Check that the runner's account may do this on Windows; the message has Windows' answer.", true);
        return r.stdout().strip();
    }

    private static final String PROCESSES_SCRIPT = "[Console]::OutputEncoding = [Text.Encoding]::UTF8; "
            + "@(Get-Process | ForEach-Object { [pscustomobject]@{ name = $_.ProcessName; pid = $_.Id; memory_mb = [math]::Round($_.WorkingSet64 / 1MB, 1); "
            + "started = $(try { $_.StartTime.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss') } catch { $null }); window_title = $_.MainWindowTitle } }) | ConvertTo-Json -Compress -Depth 2";

    @Action(id = "list_processes", name = "List processes", category = "System", idempotent = true,
            description = "List the running programs with their process id, memory use, start time and window title, optionally only those whose name contains a text.",
            panel = "name_contains | with_window_only")
    @Output(name = "processes", type = OoaType.LIST, description = "Records with name, pid, memory_mb, started (UTC) and window_title, sorted by name.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of processes listed.")
    @SuppressWarnings("unchecked")
    public Map<String, Object> listProcesses(ActionContext ctx,
            @Input(name = "name_contains", type = OoaType.STRING, required = false, example = "excel", description = "Only processes whose name contains this text (any case); all when empty.") String nameContains,
            @Input(name = "with_window_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only processes that show a window.") boolean withWindowOnly) {
        String q = nameContains == null ? "" : nameContains.strip().toLowerCase(Locale.ROOT).replaceAll("\\.exe$", "");
        List<Map<String, Object>> all = new ArrayList<>();
        if (ProcessRunner.WINDOWS) {
            String json = ps(ctx, "List processes", PROCESSES_SCRIPT, Map.of(), 60);
            Object parsed = json.isEmpty() ? List.of() : JsonLite.parse(json);
            for (Object o : parsed instanceof List<?> l ? l : List.of(parsed)) if (o instanceof Map<?, ?> m) all.add(new LinkedHashMap<>((Map<String, Object>) m));
        } else {
            ProcessHandle.allProcesses().forEach(p -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", p.info().command().map(c -> java.nio.file.Path.of(c).getFileName().toString()).orElse(""));
                m.put("pid", BigDecimal.valueOf(p.pid()));
                m.put("memory_mb", null);
                m.put("started", p.info().startInstant().map(i -> java.time.LocalDateTime.ofInstant(i, java.time.ZoneOffset.UTC).withNano(0).toString()).orElse(null));
                m.put("window_title", "");
                all.add(m);
            });
        }
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> m : all) {
            String n = String.valueOf(m.getOrDefault("name", ""));
            if (!q.isEmpty() && !n.toLowerCase(Locale.ROOT).contains(q)) continue;
            if (withWindowOnly && String.valueOf(m.getOrDefault("window_title", "")).isBlank()) continue;
            if (m.get("started") instanceof String s && !s.isEmpty()) m.put("started", java.time.LocalDateTime.parse(s));
            out.add(m);
        }
        out.sort(Comparator.comparing((Object o) -> String.valueOf(((Map<?, ?>) o).get("name")).toLowerCase(Locale.ROOT))
                .thenComparing(o -> new BigDecimal(String.valueOf(((Map<?, ?>) o).get("pid")))));
        return Out.of("processes", out, "count", BigDecimal.valueOf(out.size()));
    }

    @Action(id = "get_screen_resolution", name = "Get screen resolution", category = "System", idempotent = true, capabilities = "desktop",
            description = "Return the resolution and display scaling of the main screen, and of every screen.")
    @Output(name = "width", type = OoaType.NUMBER, description = "Main screen width in pixels, e.g. 1920.")
    @Output(name = "height", type = OoaType.NUMBER, description = "Main screen height in pixels, e.g. 1080.")
    @Output(name = "scale_percent", type = OoaType.NUMBER, description = "Main screen's display scaling, e.g. 125.")
    @Output(name = "screens", type = OoaType.LIST, description = "Every screen: width, height, scale_percent, refresh_hz, primary.")
    public Map<String, Object> getScreenResolution(ActionContext ctx) {
        if (java.awt.GraphicsEnvironment.isHeadless())
            throw new ActionException("SYSTEM.NO_DISPLAY", "This runner has no screen", "Run the agent on a runner with a signed-in desktop.");
        java.awt.GraphicsEnvironment ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment();
        java.awt.GraphicsDevice main = ge.getDefaultScreenDevice();
        List<Object> screens = new ArrayList<>();
        Map<String, Object> first = null;
        for (java.awt.GraphicsDevice d : ge.getScreenDevices()) {
            java.awt.DisplayMode dm = d.getDisplayMode();
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("width", BigDecimal.valueOf(dm.getWidth()));
            s.put("height", BigDecimal.valueOf(dm.getHeight()));
            s.put("scale_percent", BigDecimal.valueOf(Math.round(d.getDefaultConfiguration().getDefaultTransform().getScaleX() * 100)));
            s.put("refresh_hz", dm.getRefreshRate() == java.awt.DisplayMode.REFRESH_RATE_UNKNOWN ? null : BigDecimal.valueOf(dm.getRefreshRate()));
            s.put("primary", d == main);
            if (d == main) first = s;
            screens.add(s);
        }
        if (first == null) first = (Map<String, Object>) screens.get(0);
        return Out.of("width", first.get("width"), "height", first.get("height"), "scale_percent", first.get("scale_percent"), "screens", screens);
    }

    /** C# for ChangeDisplaySettingsEx on the main screen: OOA_RES_W/H/HZ set a mode for this session only (not saved); OOA_RES_RESET puts the saved one back. */
    static final String RESOLUTION_SCRIPT = String.join("\n",
            "Add-Type -TypeDefinition @'",
            "using System; using System.Runtime.InteropServices;",
            "public static class OoaDisplay {",
            "  [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)] public struct DEVMODE {",
            "    [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string dmDeviceName; public short dmSpecVersion, dmDriverVersion, dmSize, dmDriverExtra;",
            "    public int dmFields, dmPositionX, dmPositionY, dmDisplayOrientation, dmDisplayFixedOutput; public short dmColor, dmDuplex, dmYResolution, dmTTOption, dmCollate;",
            "    [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string dmFormName; public short dmLogPixels; public int dmBitsPerPel, dmPelsWidth, dmPelsHeight,",
            "    dmDisplayFlags, dmDisplayFrequency, dmICMMethod, dmICMIntent, dmMediaType, dmDitherType, dmReserved1, dmReserved2, dmPanningWidth, dmPanningHeight; }",
            "  [DllImport(\"user32.dll\", CharSet = CharSet.Unicode)] public static extern bool EnumDisplaySettings(string dev, int mode, ref DEVMODE dm);",
            "  [DllImport(\"user32.dll\", CharSet = CharSet.Unicode)] public static extern int ChangeDisplaySettingsEx(string dev, ref DEVMODE dm, IntPtr hwnd, int flags, IntPtr param);",
            "  [DllImport(\"user32.dll\", CharSet = CharSet.Unicode)] public static extern int ChangeDisplaySettingsEx(string dev, IntPtr dm, IntPtr hwnd, int flags, IntPtr param);",
            "}",
            "'@",
            "if ($env:OOA_RES_RESET) { $r = [OoaDisplay]::ChangeDisplaySettingsEx($null, [IntPtr]::Zero, [IntPtr]::Zero, 0, [IntPtr]::Zero); \"result=$r\"; exit 0 }",
            "$w = [int]$env:OOA_RES_W; $h = [int]$env:OOA_RES_H; $hz = [int]$env:OOA_RES_HZ",
            "$dm = New-Object OoaDisplay+DEVMODE; $dm.dmSize = [Runtime.InteropServices.Marshal]::SizeOf($dm); $i = 0; $found = $null",
            "while ([OoaDisplay]::EnumDisplaySettings($null, $i, [ref]$dm)) { if ($dm.dmPelsWidth -eq $w -and $dm.dmPelsHeight -eq $h -and ($hz -eq 0 -or $dm.dmDisplayFrequency -eq $hz)) { $found = $dm }; $i++ }",
            "if ($found -eq $null) { 'result=unsupported'; exit 0 }",
            "$found.dmFields = 0x80000 -bor 0x100000 -bor $(if ($hz) { 0x400000 } else { 0 })",
            "$r = [OoaDisplay]::ChangeDisplaySettingsEx($null, [ref]$found, [IntPtr]::Zero, 0, [IntPtr]::Zero); \"result=$r\"");

    /** Puts the saved screen resolution back when the run ends, after Set screen resolution with restore_at_end. */
    static final class ResolutionRestore implements AutoCloseable {
        final ActionContext ctx;
        ResolutionRestore(ActionContext ctx) { this.ctx = ctx; }
        @Override public void close() {
            try { ps(ctx, "Restore screen resolution", RESOLUTION_SCRIPT, Map.of("OOA_RES_RESET", "1"), 60); } catch (RuntimeException ignored) { /* the next sign-in restores it */ }
        }
    }

    @Action(id = "set_screen_resolution", name = "Set screen resolution", category = "System", capabilities = "desktop",
            description = "Change the main screen's resolution for this Windows session (not saved), for agents that need a fixed screen size; put back when the run ends unless turned off.",
            panel = "width, height, confirm | refresh_hz, restore_at_end")
    public Map<String, Object> setScreenResolution(ActionContext ctx,
            @Input(name = "width", type = OoaType.NUMBER, min = 640, max = 7680, example = "1920", description = "Width in pixels.") int width,
            @Input(name = "height", type = OoaType.NUMBER, min = 480, max = 4320, example = "1080", description = "Height in pixels.") int height,
            @Input(name = "confirm", type = OoaType.BOOLEAN, description = "Must be true to change the screen.") boolean confirm,
            @Input(name = "refresh_hz", type = OoaType.NUMBER, required = false, min = 1, max = 500, description = "Refresh rate, e.g. 60; the screen's choice when empty.") Integer refreshHz,
            @Input(name = "restore_at_end", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Put the saved resolution back when the run ends (also when it fails).") boolean restoreAtEnd) {
        if (!confirm) throw new ActionException("SYSTEM.CONFIRM_REQUIRED", "The screen resolution was not changed because confirm is false",
                "Set confirm to true to allow this agent to change the screen resolution.");
        Map<String, String> env = new LinkedHashMap<>();
        env.put("OOA_RES_W", String.valueOf(width));
        env.put("OOA_RES_H", String.valueOf(height));
        env.put("OOA_RES_HZ", String.valueOf(refreshHz == null ? 0 : refreshHz));
        String out = ps(ctx, "Set screen resolution", RESOLUTION_SCRIPT, env, 60);
        if (out.contains("result=unsupported"))
            throw new ActionException("SYSTEM.RESOLUTION_UNSUPPORTED", "The screen does not offer " + width + " x " + height + (refreshHz == null ? "" : " at " + refreshHz + " Hz"),
                    "Use Get screen resolution, or Windows display settings, to see the sizes the screen offers.");
        if (!out.contains("result=0"))
            throw new ActionException("SYSTEM.RESOLUTION_FAILED", "Windows did not change the resolution (" + out + ")", "A restart may be needed (result 1), or the runner has no physical screen.", true);
        if (restoreAtEnd) ctx.sessions().putIfAbsent("ooa.system.resolution", new ResolutionRestore(ctx));
        ctx.warn("Screen resolution changed to " + width + " x " + height);
        return Map.of();
    }

    @Action(id = "delete_environment_variable", name = "Delete environment variable", category = "System",
            description = "Remove an environment variable for programs this run starts (process scope) or permanently for the runner's user (user scope, Windows).",
            panel = "name, scope")
    public Map<String, Object> deleteEnv(ActionContext ctx,
            @Input(name = "name", type = OoaType.STRING, example = "REPORT_FOLDER", description = "Variable name.") String name,
            @Input(name = "scope", type = OoaType.STRING, required = false, defaultValue = "\"process\"", enumValues = {"process", "user"},
                    description = "process: programs and scripts started later in this run; user: removed for the runner's Windows user.") String scope) {
        return setEnv(ctx, name, "", scope);
    }

    @Action(id = "show_desktop", name = "Show desktop", category = "System", capabilities = "desktop",
            description = "Minimise every window so the desktop shows, like Windows+D.")
    public Map<String, Object> showDesktop(ActionContext ctx) {
        ps(ctx, "Show desktop", "(New-Object -ComObject Shell.Application).MinimizeAll()", Map.of(), 30);
        return Map.of();
    }

    @Action(id = "empty_recycle_bin", name = "Empty recycle bin", category = "System",
            description = "Delete everything in the recycle bin of one drive or of every drive. This cannot be undone.",
            panel = "confirm | drive")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of items that were in the recycle bin.")
    public Map<String, Object> emptyRecycleBin(ActionContext ctx,
            @Input(name = "confirm", type = OoaType.BOOLEAN, description = "Must be true to empty the recycle bin.") boolean confirm,
            @Input(name = "drive", type = OoaType.STRING, required = false, example = "D", description = "Drive letter; every drive when empty.") String drive) {
        if (!confirm) throw new ActionException("SYSTEM.CONFIRM_REQUIRED", "The recycle bin was not emptied because confirm is false",
                "Set confirm to true to allow this agent to delete the recycle bin's contents for good.");
        String d = drive == null ? "" : drive.strip().replaceAll("[:\\\\/]+$", "");
        if (!d.isEmpty() && !d.matches("[A-Za-z]")) throw new ActionException("SYSTEM.BAD_DRIVE", "'" + drive + "' is not a drive letter", "Give one letter, e.g. D.");
        String out = ps(ctx, "Empty recycle bin", "$n = (New-Object -ComObject Shell.Application).NameSpace(10).Items().Count; "
                + "if ($env:OOA_DRIVE) { Clear-RecycleBin -DriveLetter $env:OOA_DRIVE -Force -ErrorAction SilentlyContinue } else { Clear-RecycleBin -Force -ErrorAction SilentlyContinue }; \"count=$n\"",
                d.isEmpty() ? Map.of() : Map.of("OOA_DRIVE", d.toUpperCase(Locale.ROOT)), 300);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("count=(\\d+)").matcher(out);
        ctx.warn("Recycle bin emptied" + (d.isEmpty() ? "" : " on drive " + d.toUpperCase(Locale.ROOT)));
        return Out.of("count", BigDecimal.valueOf(m.find() ? Long.parseLong(m.group(1)) : 0));
    }

    /** A Windows toast through PowerShell's own app id; title and message arrive as environment variables and are XML-escaped. */
    static final String TOAST_SCRIPT = String.join("\n",
            "[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null",
            "[Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime] | Out-Null",
            "$e = { param($t) [Security.SecurityElement]::Escape($t) }",
            "$xml = New-Object Windows.Data.Xml.Dom.XmlDocument",
            "$xml.LoadXml(\"<toast><visual><binding template='ToastGeneric'><text>$(& $e $env:OOA_TITLE)</text><text>$(& $e $env:OOA_MESSAGE)</text></binding></visual></toast>\")",
            "$app = '{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}\\WindowsPowerShell\\v1.0\\powershell.exe'",
            "[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier($app).Show([Windows.UI.Notifications.ToastNotification]::new($xml))",
            "'shown'");

    @Action(id = "notify", name = "Desktop notification", category = "System", capabilities = "desktop",
            description = "Show a Windows notification with a title and a message to the person signed in on the runner; the agent does not wait.",
            panel = "title, message")
    public Map<String, Object> notify(ActionContext ctx,
            @Input(name = "title", type = OoaType.STRING, example = "Invoices done", description = "Title in bold.") String title,
            @Input(name = "message", type = OoaType.STRING, required = false, example = "42 invoices posted, 2 need checking.", description = "Text under the title.") String message) {
        if (title == null || title.isBlank()) throw new ActionException("SYSTEM.NO_TITLE", "The notification needs a title", "Fill in the title.");
        Map<String, String> env = new LinkedHashMap<>();
        env.put("OOA_TITLE", title.length() > 200 ? title.substring(0, 200) : title);
        env.put("OOA_MESSAGE", message == null ? "" : message.length() > 1000 ? message.substring(0, 1000) : message);
        ps(ctx, "Desktop notification", TOAST_SCRIPT, env, 60);
        return Map.of();
    }
}
