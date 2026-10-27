package com.oneofficeautomation.javaagent;

import javax.accessibility.*;
import javax.swing.SwingUtilities;
import java.awt.*;
import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loaded into a running Java application by the ONEOFFICEAUTOMATION runner (Java Attach API), so agents can work
 * with its Swing and AWT controls through their accessibility information, as screen readers do, without the Java
 * Access Bridge being switched on. It listens on 127.0.0.1 only and answers only requests that start with the token
 * it writes, with the port, to the file the runner names (in the runner user's temporary folder).
 *
 * <p>Requests are one line each: a token, then fields separated by tabs (tabs and line breaks in values are written
 * as \t and \n). Answers are one line of JSON. Written for Java 8, the oldest Java the applications may run on.
 */
public final class OoaJavaAgent {
    private OoaJavaAgent() { }

    private static volatile ServerSocket server;
    private static String token;

    public static void agentmain(String args) throws Exception {
        synchronized (OoaJavaAgent.class) {
            if (server == null) {
                byte[] b = new byte[24];
                new SecureRandom().nextBytes(b);
                StringBuilder t = new StringBuilder();
                for (byte x : b) t.append(String.format("%02x", x & 0xff));
                token = t.toString();
                server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
                Thread th = new Thread(new Runnable() { public void run() { serve(); } }, "ooa-java-agent");
                th.setDaemon(true);
                th.start();
            }
        }
        // the runner reads the port and token from this file (written again when loaded a second time)
        Writer w = new OutputStreamWriter(new FileOutputStream(args), StandardCharsets.UTF_8);
        try { w.write(server.getLocalPort() + "\n" + token + "\n"); } finally { w.close(); }
    }

    static void serve() {
        while (true) {
            try {
                final Socket s = server.accept();
                Thread th = new Thread(new Runnable() { public void run() { handle(s); } }, "ooa-java-agent-conn");
                th.setDaemon(true);
                th.start();
            } catch (IOException e) { return; }
        }
    }

    static void handle(Socket s) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            Writer out = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
            for (String line; (line = in.readLine()) != null; ) {
                String[] f = line.split("\t", -1);
                for (int i = 0; i < f.length; i++) f[i] = unescape(f[i]);
                String answer;
                if (f.length < 2 || !token.equals(f[0])) { answer = "{\"ok\":false,\"error\":\"refused\"}"; out.write(answer + "\n"); out.flush(); break; }
                try { answer = "{\"ok\":true,\"result\":" + onEdt(f) + "}"; }
                catch (Throwable e) { answer = "{\"ok\":false,\"error\":" + q(String.valueOf(e.getMessage() == null ? e : e.getMessage())) + "}"; }
                out.write(answer + "\n");
                out.flush();
            }
        } catch (IOException ignored) {
        } finally { try { s.close(); } catch (IOException ignored) { } }
    }

    /** Swing is not thread-safe: every request runs on the event dispatch thread. */
    static String onEdt(final String[] f) throws Exception {
        final AtomicReference<Object> r = new AtomicReference<Object>();
        SwingUtilities.invokeAndWait(new Runnable() { public void run() {
            try { r.set(dispatch(f)); } catch (Throwable e) { r.set(e); }
        } });
        if (r.get() instanceof Throwable) throw new IllegalStateException(((Throwable) r.get()).getMessage(), (Throwable) r.get());
        return (String) r.get();
    }

    static String dispatch(String[] f) {
        String op = f[1];
        if (op.equals("ping")) return q("pong");
        if (op.equals("windows")) {
            StringBuilder sb = new StringBuilder("[");
            for (Window w : windows("")) {
                if (sb.length() > 1) sb.append(',');
                sb.append("{\"title\":").append(q(title(w))).append(",\"class\":").append(q(w.getClass().getName())).append('}');
            }
            return sb.append(']').toString();
        }
        if (op.equals("find")) {   // find  window  name  role  max
            List<String> out = new ArrayList<String>();
            List<Window> ws = windows(arg(f, 2));
            int max = Integer.parseInt(arg(f, 5).isEmpty() ? "500" : arg(f, 5));
            for (int i = 0; i < ws.size() && out.size() < max; i++) {
                AccessibleContext ac = ws.get(i).getAccessibleContext();
                if (ac != null) walk(ac, String.valueOf(i), arg(f, 3), arg(f, 4), out, max, 0);
            }
            StringBuilder sb = new StringBuilder("[");
            for (String o : out) { if (sb.length() > 1) sb.append(','); sb.append(o); }
            return sb.append(']').toString();
        }
        if (op.equals("act")) {    // act  window  path  action  value
            AccessibleContext ac = byPath(windows(arg(f, 2)), arg(f, 3));
            String action = arg(f, 4);
            if (action.equals("properties")) return props(ac, arg(f, 3));
            if (action.equals("get_text")) return q(text(ac));
            if (action.equals("set_text")) {
                AccessibleEditableText et = ac.getAccessibleEditableText();
                if (et == null) throw new IllegalArgumentException("The control does not take text");
                et.setTextContents(arg(f, 5));
                return "true";
            }
            if (action.equals("do_action")) {
                AccessibleAction aa = ac.getAccessibleAction();
                if (aa == null || aa.getAccessibleActionCount() == 0) throw new IllegalArgumentException("The control has no action");
                int k = 0;
                String want = arg(f, 5);
                if (!want.isEmpty()) {
                    k = -1;
                    for (int i = 0; i < aa.getAccessibleActionCount(); i++) if (want.equalsIgnoreCase(aa.getAccessibleActionDescription(i))) k = i;
                    if (k < 0) throw new IllegalArgumentException("The control has no action '" + want + "'");
                }
                return String.valueOf(aa.doAccessibleAction(k));
            }
            if (action.equals("focus")) {
                AccessibleComponent c = ac.getAccessibleComponent();
                if (c == null) throw new IllegalArgumentException("The control cannot take the focus");
                c.requestFocus();
                return "true";
            }
            throw new IllegalArgumentException("Unknown action '" + action + "'");
        }
        throw new IllegalArgumentException("Unknown request '" + op + "'");
    }

    /** Undoes the sender's escaping: \\ for a backslash, \t for a tab, \n for a line break. */
    static String unescape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(n == 't' ? '\t' : n == 'n' ? '\n' : n);
            } else sb.append(c);
        }
        return sb.toString();
    }

    static String arg(String[] f, int i) { return i < f.length ? f[i] : ""; }

    static List<Window> windows(String pattern) {
        List<Window> out = new ArrayList<Window>();
        for (Window w : Window.getWindows()) if (w.isShowing() && like(title(w), pattern)) out.add(w);
        return out;
    }

    static String title(Window w) {
        if (w instanceof Frame) return ((Frame) w).getTitle();
        if (w instanceof Dialog) return ((Dialog) w).getTitle();
        return "";
    }

    static void walk(AccessibleContext ac, String path, String name, String role, List<String> out, int max, int depth) {
        if (out.size() >= max || depth > 40) return;
        if (path.indexOf('.') > 0 && like(ac.getAccessibleName(), name) && like(role(ac), role)) out.add(props(ac, path));
        int n = ac.getAccessibleChildrenCount();
        for (int i = 0; i < n && out.size() < max; i++) {
            Accessible child = ac.getAccessibleChild(i);
            AccessibleContext cc = child == null ? null : child.getAccessibleContext();
            if (cc != null) walk(cc, path + "." + i, name, role, out, max, depth + 1);
        }
    }

    static AccessibleContext byPath(List<Window> ws, String path) {
        String[] p = path.split("\\.");
        int w = Integer.parseInt(p[0]);
        if (w >= ws.size()) throw new IllegalArgumentException("No window " + w + " (the application changed)");
        AccessibleContext ac = ws.get(w).getAccessibleContext();
        for (int i = 1; i < p.length; i++) {
            int k = Integer.parseInt(p[i]);
            if (ac == null || k >= ac.getAccessibleChildrenCount()) throw new IllegalArgumentException("No control at " + path + " (the application changed)");
            Accessible a = ac.getAccessibleChild(k);
            ac = a == null ? null : a.getAccessibleContext();
        }
        if (ac == null) throw new IllegalArgumentException("No control at " + path);
        return ac;
    }

    static String role(AccessibleContext ac) {
        AccessibleRole r = ac.getAccessibleRole();
        return r == null ? "" : r.toDisplayString(Locale.ENGLISH);
    }

    static String text(AccessibleContext ac) {
        AccessibleText t = ac.getAccessibleText();
        if (t instanceof AccessibleEditableText) return ((AccessibleEditableText) t).getTextRange(0, t.getCharCount());
        if (t != null && t.getCharCount() > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < t.getCharCount(); i++) sb.append(t.getAtIndex(AccessibleText.CHARACTER, i));
            return sb.toString();
        }
        AccessibleValue v = ac.getAccessibleValue();
        if (v != null && v.getCurrentAccessibleValue() != null) return String.valueOf(v.getCurrentAccessibleValue());
        return ac.getAccessibleName() == null ? "" : ac.getAccessibleName();
    }

    static String props(AccessibleContext ac, String path) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"path\":").append(q(path));
        sb.append(",\"name\":").append(q(ac.getAccessibleName()));
        sb.append(",\"role\":").append(q(role(ac)));
        sb.append(",\"description\":").append(q(ac.getAccessibleDescription()));
        String text = "";
        try { text = text(ac); } catch (RuntimeException ignored) { }
        sb.append(",\"text\":").append(q(text));
        AccessibleStateSet st = ac.getAccessibleStateSet();
        sb.append(",\"enabled\":").append(st != null && st.contains(AccessibleState.ENABLED));
        sb.append(",\"visible\":").append(st != null && st.contains(AccessibleState.SHOWING));
        sb.append(",\"editable\":").append(ac.getAccessibleEditableText() != null && st != null && st.contains(AccessibleState.EDITABLE));
        sb.append(",\"checked\":").append(st != null && st.contains(AccessibleState.CHECKED));
        sb.append(",\"actions\":[");
        AccessibleAction aa = ac.getAccessibleAction();
        if (aa != null) for (int i = 0; i < aa.getAccessibleActionCount(); i++) { if (i > 0) sb.append(','); sb.append(q(aa.getAccessibleActionDescription(i))); }
        sb.append(']');
        AccessibleComponent c = ac.getAccessibleComponent();
        String bounds = "";
        try {
            if (c != null && c.getLocationOnScreen() != null && c.getSize() != null)
                bounds = c.getLocationOnScreen().x + "," + c.getLocationOnScreen().y + "," + c.getSize().width + "," + c.getSize().height;
        } catch (RuntimeException ignored) { }
        sb.append(",\"bounds\":").append(q(bounds));
        return sb.append('}').toString();
    }

    /** Empty matches everything; * and ? are wildcards; otherwise a case-insensitive "contains". */
    static boolean like(String text, String pattern) {
        if (pattern == null || pattern.isEmpty()) return true;
        String t = text == null ? "" : text;
        if (pattern.indexOf('*') < 0 && pattern.indexOf('?') < 0) return t.toLowerCase(Locale.ROOT).contains(pattern.toLowerCase(Locale.ROOT));
        StringBuilder rx = new StringBuilder("(?i)");
        for (char ch : pattern.toCharArray()) {
            if (ch == '*') rx.append(".*"); else if (ch == '?') rx.append('.');
            else rx.append(java.util.regex.Pattern.quote(String.valueOf(ch)));
        }
        return t.matches(rx.toString());
    }

    static String q(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c == '\n') sb.append("\\n"); else if (c == '\r') sb.append("\\r"); else if (c == '\t') sb.append("\\t");
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }
}
