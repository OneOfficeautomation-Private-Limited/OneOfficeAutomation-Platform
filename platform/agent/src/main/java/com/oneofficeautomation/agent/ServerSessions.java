package com.oneofficeautomation.agent;

import com.oneofficeautomation.sdk.OoaException;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Several unattended sessions on one Windows Server (gap G50). Each robot account has its own Windows session with its
 * own runner (registered once with `ooa agent register --config ...` and started at sign-in with `ooa agent
 * install-task` in that account), so the server takes several screen runs at the same time.
 * <p>
 * `ooa agent sessions` opens those sessions: a Remote Desktop connection from this server to itself for each robot
 * account, each to its own loopback address (127.0.0.2, 127.0.0.3, ...) so Windows keeps a saved credential for each.
 * The passwords come from environment variables and are kept by Windows Credential Manager (cmdkey), protected for the
 * administrator who runs the command. With --watch it checks every minute and opens a session again when one is gone.
 * Needs Windows Server with Remote Desktop Services (or as many sessions as the licence allows); a Windows client
 * edition allows one session.
 */
public final class ServerSessions {
    private ServerSessions() { }

    /** Runs a program; tests replace it. Returns {exit code, output}. */
    interface Exec { Object[] run(List<String> command) throws IOException; }

    static Exec exec = command -> {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), Charset.defaultCharset());
        try { p.waitFor(60, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return new Object[]{p.isAlive() ? -1 : p.exitValue(), out};
    };

    /** The loopback address for the i-th robot account (0-based): 127.0.0.2 onwards. */
    static String address(int i) {
        if (i < 0 || i > 250) throw new OoaException("AGENT.BAD_INPUT", "At most 250 sessions");
        return "127.0.0." + (i + 2);
    }

    /** The environment variable with a robot account's password: PREFIX + the name in capitals, other characters as _. */
    static String passwordVariable(String prefix, String user) {
        String name = user.contains("\\") ? user.substring(user.indexOf('\\') + 1) : user;
        return prefix + name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
    }

    /** The user names of the sessions open on this server now (from `query user`), lower case, without domain. */
    static Set<String> openSessions() throws IOException {
        Object[] r = exec.run(List.of("query", "user"));
        Set<String> out = new HashSet<>();
        String[] lines = String.valueOf(r[1]).split("\\r?\\n");
        for (int i = 1; i < lines.length; i++) {
            String t = lines[i].strip();
            if (t.startsWith(">")) t = t.substring(1);
            if (t.isBlank()) continue;
            String user = t.split("\\s+")[0];
            boolean disconnected = t.toLowerCase(Locale.ROOT).contains(" disc ");
            if (!disconnected) out.add(user.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** Opens the session of one robot account when it is not open: saves its credential for its address, then connects. */
    static String open(String user, String address, String password, int width, int height) throws IOException {
        if (!user.matches("[A-Za-z0-9._\\-]{1,64}(\\\\[A-Za-z0-9._\\-]{1,104})?")) throw new OoaException("AGENT.BAD_INPUT", "Not an account name: " + user);
        Object[] k = exec.run(List.of("cmdkey", "/generic:TERMSRV/" + address, "/user:" + user, "/pass:" + password));
        if ((int) k[0] != 0) throw new OoaException("AGENT.SESSION_FAILED", "Windows did not keep the credential for " + user + ": " + String.valueOf(k[1]).strip());
        exec.run(List.of("cmd", "/c", "start", "\"\"", "/min", "mstsc", "/v:" + address, "/w:" + width, "/h:" + height));
        return "Opening the session of " + user + " (" + address + ")";
    }

    /** ooa agent sessions --users a,b [--password-env-prefix P] [--width W --height H] [--watch] [--check]. */
    public static int run(Map<String, String> o, java.io.PrintStream out) throws IOException, InterruptedException {
        if (!DesktopSession.WINDOWS) throw new OoaException("SYSTEM.WINDOWS_ONLY", "Server sessions are for Windows Server runners");
        List<String> users = new ArrayList<>();
        for (String u : String.valueOf(o.getOrDefault("users", "")).split("[,;\\s]+")) if (!u.isBlank()) users.add(u.strip());
        if (users.isEmpty()) throw new OoaException("AGENT.BAD_INPUT", "Name the robot accounts with --users robot1,robot2");
        String prefix = o.getOrDefault("password-env-prefix", "OOA_SESSION_PW_");
        int w = Integer.parseInt(o.getOrDefault("width", "1920")), h = Integer.parseInt(o.getOrDefault("height", "1080"));
        boolean check = o.containsKey("check"), watch = o.containsKey("watch");
        do {
            Set<String> open = openSessions();
            for (int i = 0; i < users.size(); i++) {
                String u = users.get(i), bare = (u.contains("\\") ? u.substring(u.indexOf('\\') + 1) : u).toLowerCase(Locale.ROOT);
                if (open.contains(bare)) { out.println(u + ": signed in"); continue; }
                if (check) { out.println(u + ": no session"); continue; }
                String pw = System.getenv(passwordVariable(prefix, u));
                if (pw == null || pw.isEmpty()) { out.println(u + ": no session, and " + passwordVariable(prefix, u) + " is not set"); continue; }
                out.println(open(u, address(i), pw, w, h));
            }
            if (watch) Thread.sleep(60_000);
        } while (watch);
        return 0;
    }
}
