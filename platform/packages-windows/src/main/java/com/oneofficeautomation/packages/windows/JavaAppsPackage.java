package com.oneofficeautomation.packages.windows;

import com.oneofficeautomation.sdk.*;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

import java.io.*;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Desktop applications written in Java (Swing and AWT), through their accessibility information (X6). The runner
 * loads a small agent into the application's JVM with the Java Attach API, so the Java Access Bridge need not be
 * switched on; the agent answers on 127.0.0.1 only, to requests carrying the token it gave this runner. It works for
 * applications running as the same user as the runner, on Java 8 or later, that allow agents to be loaded.
 */
@OoaPackage(id = "ooa.java_apps", name = "Java applications (Swing/AWT)", version = "1.0.0",
        description = "Find and work with the controls of desktop applications written in Java (Swing and AWT): read and type text, press buttons, read labels and tables.")
public final class JavaAppsPackage {
    static final String P = "JAVA_APPS";
    /** Java programs that are tools, not applications an agent works in; left alone unless named in process. */
    static final List<String> TOOLS = List.of("gradle", "org.jetbrains", "jetbrains", "maven", "plexus-classworlds", "sun.tools", "jdk.jcmd",
        "com.oneofficeautomation", "org.eclipse.equinox", "surefire", "junit", "kotlin.daemon");

    record Conn(String pid, String name, int port, String token) {}

    private static final Map<String, Conn> CONNECTED = new ConcurrentHashMap<>();
    private static volatile Path agentJar;

    static synchronized Path agentJar() throws IOException {
        if (agentJar != null && Files.exists(agentJar)) return agentJar;
        try (InputStream in = JavaAppsPackage.class.getResourceAsStream("ooa-java-agent.jar")) {
            if (in == null) throw new ActionException(P + ".NOT_INSTALLED", "The Java applications agent is missing from this runner", "Reinstall the runner.");
            Path f = Files.createTempFile("ooa-java-agent", ".jar");
            Files.write(f, in.readAllBytes());
            f.toFile().deleteOnExit();
            return agentJar = f;
        }
    }

    /** Java programs running as this user, other than this one, whose name contains process (tools skipped when it is empty). */
    static List<VirtualMachineDescriptor> candidates(String process) {
        String self = String.valueOf(ProcessHandle.current().pid());
        List<VirtualMachineDescriptor> out = new ArrayList<>();
        for (VirtualMachineDescriptor d : VirtualMachine.list()) {
            if (d.id().equals(self)) continue;
            String name = d.displayName() == null ? "" : d.displayName().toLowerCase(Locale.ROOT);
            if (process != null && !process.isBlank()) { if (name.contains(process.strip().toLowerCase(Locale.ROOT))) out.add(d); }
            else if (TOOLS.stream().noneMatch(name::contains)) out.add(d);
        }
        return out;
    }

    /** Loads the agent into the program once (per runner process) and returns how to reach it. */
    static Conn connect(VirtualMachineDescriptor d) {
        Conn c = CONNECTED.get(d.id());
        if (c != null) return c;
        try {
            Path portFile = Files.createTempFile("ooa-java-agent", ".port");
            VirtualMachine vm = VirtualMachine.attach(d);
            try { vm.loadAgent(agentJar().toString(), portFile.toString()); } finally { vm.detach(); }
            List<String> lines = Files.readAllLines(portFile, StandardCharsets.UTF_8);
            Files.deleteIfExists(portFile);
            if (lines.size() < 2) throw new ActionException(P + ".ATTACH_FAILED", "The Java application did not answer the agent");
            c = new Conn(d.id(), d.displayName(), Integer.parseInt(lines.get(0).strip()), lines.get(1).strip());
            CONNECTED.put(d.id(), c);
            return c;
        } catch (ActionException e) { throw e;
        } catch (Exception e) {
            throw new ActionException(P + ".ATTACH_FAILED", "Cannot connect to the Java application " + d.displayName() + ": " + e.getMessage(),
                "The application must run as the same user as the runner, on Java 8 or later, and allow agents (no -XX:-EnableDynamicAgentLoading).", true);
        }
    }

    static String esc(String s) { return (s == null ? "" : s).replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n"); }

    @SuppressWarnings("unchecked")
    static Object request(Conn c, String... fields) {
        StringBuilder line = new StringBuilder(c.token());
        for (String f : fields) line.append('\t').append(esc(f));
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), c.port())) {
            s.setSoTimeout(30_000);
            Writer w = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
            w.write(line + "\n");
            w.flush();
            String answer = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine();
            Map<String, Object> m = (Map<String, Object>) JsonLite.parse(answer == null ? "{}" : answer);
            if (!Boolean.TRUE.equals(m.get("ok"))) throw new ActionException(P + ".FAILED", "The Java application refused: " + m.get("error"), null);
            return m.get("result");
        } catch (IOException e) {
            CONNECTED.remove(c.pid());   // the application closed or restarted: connect again next time
            throw new ActionException(P + ".NOT_REACHABLE", "The Java application " + c.name() + " no longer answers: " + e.getMessage(), "Check it is still open.", true);
        }
    }

    /** The programs that have a window matching title, with their windows. */
    @SuppressWarnings("unchecked")
    static List<Conn> withWindow(String title, String process) {
        List<Conn> out = new ArrayList<>();
        for (VirtualMachineDescriptor d : candidates(process)) {
            try {
                Conn c = connect(d);
                if (hasWindow(c, title)) out.add(c);
            } catch (ActionException ignored) { /* a program that cannot be reached is skipped */ }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static boolean hasWindow(Conn c, String title) {
        for (Object o : (List<Object>) request(c, "windows"))
            if (JavaWindows.like(String.valueOf(((Map<String, Object>) o).get("title")), title)) return true;
        return false;
    }

    /** Same matching as the agent: empty for any, * and ? wildcards, else contains, ignoring case. */
    static final class JavaWindows {
        static boolean like(String text, String pattern) {
            if (pattern == null || pattern.isBlank()) return true;
            String t = text == null ? "" : text;
            if (!pattern.contains("*") && !pattern.contains("?")) return t.toLowerCase(Locale.ROOT).contains(pattern.toLowerCase(Locale.ROOT));
            StringBuilder rx = new StringBuilder("(?i)");
            for (char ch : pattern.toCharArray()) rx.append(ch == '*' ? ".*" : ch == '?' ? "." : java.util.regex.Pattern.quote(String.valueOf(ch)));
            return t.matches(rx.toString());
        }
    }

    private static final String D_TITLE = "Title of the application's window: text it contains, or a pattern with * and ?.";
    private static final String D_PROCESS = "Part of the Java program's name (its main class or jar, as Get Java applications lists it); narrows the search and is needed for programs named like tools.";
    private static final String D_ROLE = "Java accessibility role, e.g. push button, text, label, check box, combo box, table (contains, or * and ?).";

    @SuppressWarnings("unchecked")
    @Action(id = "list_apps", name = "Get Java applications", category = "Java applications", idempotent = true, capabilities = "desktop",
            description = "List the Java programs running as the runner's user that have windows, with their window titles.", panel = "process")
    @Output(name = "apps", type = OoaType.LIST, description = "One dictionary per program: pid, name and windows (their titles).")
    public Map<String, Object> listApps(ActionContext ctx, @Input(name = "process", type = OoaType.STRING, required = false, description = D_PROCESS) String process) {
        List<Object> apps = new ArrayList<>();
        for (VirtualMachineDescriptor d : candidates(process)) {
            try {
                Conn c = connect(d);
                List<Object> titles = new ArrayList<>();
                for (Object o : (List<Object>) request(c, "windows")) titles.add(((Map<String, Object>) o).get("title"));
                if (!titles.isEmpty()) apps.add(Map.of("pid", c.pid(), "name", c.name(), "windows", titles));
            } catch (ActionException ignored) { /* not reachable */ }
        }
        return Map.of("apps", apps);
    }

    @SuppressWarnings("unchecked")
    static List<Object> find(ActionContext ctx, String title, String process, String name, String role, int max, double timeout) {
        long end = System.currentTimeMillis() + (long) (Math.max(0, timeout) * 1000);
        while (true) {
            List<Object> out = new ArrayList<>();
            for (Conn c : withWindow(title, process))
                for (Object o : (List<Object>) request(c, "find", title == null ? "" : title, name == null ? "" : name, role == null ? "" : role, String.valueOf(max))) {
                    Map<String, Object> m = new LinkedHashMap<>((Map<String, Object>) o);
                    m.put("pid", c.pid());
                    out.add(m);
                }
            if (!out.isEmpty() || System.currentTimeMillis() >= end) return out;
            ctx.checkCancelled();
            try { Thread.sleep(400); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return out; }
        }
    }

    @Action(id = "find_objects", name = "Find Java objects", category = "Java applications", idempotent = true, timeoutSeconds = 600, capabilities = "desktop",
            description = "List the controls of a Java application's window: name, role, text, state, actions and position, each with its path.",
            panel = "window_title, name, role | process, max, timeout_seconds")
    @Output(name = "objects", type = OoaType.LIST, description = "Matching controls: path, name, role, description, text, enabled, visible, editable, checked, actions, bounds, pid.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of matching controls.")
    public Map<String, Object> findObjects(ActionContext ctx,
            @Input(name = "window_title", type = OoaType.STRING, required = false, description = D_TITLE) String title,
            @Input(name = "name", type = OoaType.STRING, required = false, description = "Name of the control (contains, or * and ?); empty for any.") String name,
            @Input(name = "role", type = OoaType.STRING, required = false, description = D_ROLE) String role,
            @Input(name = "process", type = OoaType.STRING, required = false, description = D_PROCESS) String process,
            @Input(name = "max", type = OoaType.NUMBER, required = false, defaultValue = "500", min = 1, max = 5000, description = "At most this many controls.") int max,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = "How long to wait for at least one match, in seconds; 0 looks once.") double timeout) {
        List<Object> list = find(ctx, title, process, name, role, max, timeout);
        return Map.of("objects", list, "count", BigDecimal.valueOf(list.size()));
    }

    @SuppressWarnings("unchecked")
    @Action(id = "object_action", name = "Java object action", category = "Java applications", timeoutSeconds = 600, capabilities = "desktop",
            description = "Act on a control of a Java application: press it (its action), type text into it, read its text, give it the focus, or read its properties. Find it by name and role, or by path.",
            panel = "window_title, (Find the control by: Name and role = name, role, index / Path = path), action, value | process, timeout_seconds")
    @Output(name = "result", description = "The text read (get_text), the properties (get_properties), or true.")
    @Output(name = "object", type = OoaType.DICTIONARY, description = "The control acted on: path, name, role, text and the rest.")
    public Map<String, Object> objectAction(ActionContext ctx,
            @Input(name = "window_title", type = OoaType.STRING, required = false, description = D_TITLE) String title,
            @Input(name = "name", type = OoaType.STRING, required = false, description = "Name of the control (contains, or * and ?).") String name,
            @Input(name = "role", type = OoaType.STRING, required = false, description = D_ROLE) String role,
            @Input(name = "index", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = "Which match to use when several do, 0 for the first.") int index,
            @Input(name = "path", type = OoaType.STRING, required = false, example = "0.0.1.2", description = "The control's path from Find Java objects, instead of name and role.") String path,
            @Input(name = "action", type = OoaType.STRING, required = false, defaultValue = "\"do_action\"", enumValues = {"do_action", "set_text", "get_text", "focus", "get_properties"},
                    description = "do_action presses a button, toggles a box or opens a menu; set_text replaces the text of a field.") String action,
            @Input(name = "value", type = OoaType.STRING, required = false, description = "The text to type (set_text), or the name of the action to do when the control has several.") String value,
            @Input(name = "process", type = OoaType.STRING, required = false, description = D_PROCESS) String process,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "10", min = 0, description = "How long to wait for the control, in seconds.") double timeout) {
        String act = action == null || action.isBlank() ? "do_action" : action;
        Map<String, Object> target;
        Conn conn;
        if (path != null && !path.isBlank()) {
            List<Conn> cs = withWindow(title, process);
            if (cs.isEmpty()) throw notFound(title, name, role);
            conn = cs.get(0);
            target = (Map<String, Object>) request(conn, "act", title == null ? "" : title, path.strip(), "properties", "");
        } else {
            if ((name == null || name.isBlank()) && (role == null || role.isBlank())) throw new ActionException(P + ".BAD_INPUT", "Give the control's name, role or path");
            List<Object> found = find(ctx, title, process, name, role, index + 1, timeout);
            if (found.size() <= index) throw notFound(title, name, role);
            target = (Map<String, Object>) found.get(index);
            conn = CONNECTED.get(String.valueOf(target.get("pid")));
        }
        Object result = act.equals("get_properties") ? target
            : request(conn, "act", title == null ? "" : title, String.valueOf(target.get("path")), act, value == null ? "" : value);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("result", result);
        out.put("object", target);
        return out;
    }

    static ActionException notFound(String title, String name, String role) {
        return new ActionException(P + ".OBJECT_NOT_FOUND", "No control " + (name == null || name.isBlank() ? "" : "named '" + name + "' ") + (role == null || role.isBlank() ? "" : "with role '" + role + "' ")
            + "in a Java window" + (title == null || title.isBlank() ? "" : " titled '" + title + "'"), "Use Find Java objects to see the controls, or Get Java applications to see which programs are reachable.", true);
    }
}
