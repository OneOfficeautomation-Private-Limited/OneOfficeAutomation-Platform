package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.JsonLite;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/**
 * Shows open forms in a small browser window on this computer and passes the person's clicks and changes back to the
 * agent. The server listens on 127.0.0.1 only, and each form has its own unguessable address, so other people and
 * other computers cannot read or answer it.
 */
final class FormWindow {
    private FormWindow() {}

    /** Set to false (tests, or a runner that shows the address some other way) to open no browser window. */
    static volatile boolean openWindows = !"false".equals(System.getProperty("ooa.forms.open"));

    private static HttpServer server;
    private static final Map<String, Session> BY_TOKEN = new ConcurrentHashMap<>();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** One open form: its definition, the current answers, what the agent changed on it, and the person's events. */
    static final class Session implements AutoCloseable {
        final String token, name;
        Map<String, Object> form;
        String title;
        final Map<String, Object> values = new LinkedHashMap<>();
        /** Per element: hidden, disabled, highlight, label, options (what the agent changed while the form is open). */
        final Map<String, Map<String, Object>> elements = new LinkedHashMap<>();
        final Map<String, String> errors = new LinkedHashMap<>();
        String focus;
        long version = 1;
        boolean closed;
        final BlockingQueue<Map<String, Object>> events = new LinkedBlockingQueue<>();

        Session(String name, Map<String, Object> form, String title) {
            byte[] b = new byte[24];
            RANDOM.nextBytes(b);
            this.token = HexFormat.of().formatHex(b);
            this.name = name;
            this.form = form;
            this.title = title;
        }

        synchronized void changed() { version++; notifyAll(); }

        Map<String, Object> el(String id) { return elements.computeIfAbsent(id, k -> new LinkedHashMap<>()); }

        synchronized Map<String, Object> snapshot() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("version", version); m.put("closed", closed); m.put("title", title); m.put("form", form);
            m.put("values", new LinkedHashMap<>(values)); m.put("elements", new LinkedHashMap<>(elements));
            m.put("errors", new LinkedHashMap<>(errors)); m.put("focus", focus);
            focus = null;   // focus is a one-time request
            return m;
        }

        /** Waits (up to ms) until the form changes after `since`; returns the new state. */
        synchronized Map<String, Object> waitForChange(long since, long ms) throws InterruptedException {
            long end = System.currentTimeMillis() + ms;
            while (version <= since && !closed) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) break;
                wait(left);
            }
            return snapshot();
        }

        String address() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/form/" + token; }

        @Override public void close() {
            synchronized (this) { closed = true; changed(); }
            events.offer(Map.of("type", "close", "by", "agent"));
            BY_TOKEN.remove(token);
        }
    }

    static synchronized Session open(String name, Map<String, Object> form, String title) {
        if (server == null) {
            try {
                server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
                server.setExecutor(Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "ooa-forms"); t.setDaemon(true); return t; }));
                server.createContext("/form/", FormWindow::handle);
                server.start();
            } catch (IOException e) { throw new ActionException("FORMS.SERVER_FAILED", "Cannot show forms on this computer: " + e.getMessage(), null); }
        }
        Session s = new Session(name, form, title);
        BY_TOKEN.put(s.token, s);
        return s;
    }

    /** Opens the form in a small app-style browser window (Edge or Chrome), or the default browser. */
    static void show(Session s, int width, int height) {
        if (!openWindows) return;
        if (java.awt.GraphicsEnvironment.isHeadless())
            throw new ActionException("FORMS.NO_DESKTOP", "Forms need a runner with a desktop and a signed-in person", "Run this agent attended, on a runner where someone is signed in.");
        String url = s.address();
        List<String> browsers = ProcessRunner.WINDOWS
                ? List.of(System.getenv().getOrDefault("ProgramFiles(x86)", "C:\\Program Files (x86)") + "\\Microsoft\\Edge\\Application\\msedge.exe",
                        System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files") + "\\Microsoft\\Edge\\Application\\msedge.exe",
                        System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files") + "\\Google\\Chrome\\Application\\chrome.exe")
                : List.of("/usr/bin/microsoft-edge", "/usr/bin/google-chrome", "/usr/bin/chromium");
        for (String b : browsers) {
            if (!Files.isRegularFile(Path.of(b))) continue;
            try { new ProcessBuilder(b, "--app=" + url, "--window-size=" + width + "," + height, "--new-window").start(); return; }
            catch (IOException ignored) { /* try the next one */ }
        }
        try {
            if (ProcessRunner.WINDOWS) new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            else java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
        } catch (Exception e) { throw new ActionException("FORMS.NO_BROWSER", "Cannot open a window for the form: " + e.getMessage(), "Install Microsoft Edge or Google Chrome on the runner."); }
    }

    @SuppressWarnings("unchecked")
    private static void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String[] parts = ex.getRequestURI().getPath().split("/");   // "", "form", token, [state|event]
            Session s = parts.length >= 3 ? BY_TOKEN.get(parts[2]) : null;
            if (s == null) { send(ex, 404, "text/plain; charset=utf-8", "This form is closed.".getBytes(StandardCharsets.UTF_8)); return; }
            String what = parts.length >= 4 ? parts[3] : "";
            if (what.isEmpty() && ex.getRequestMethod().equals("GET")) {
                try (InputStream in = FormWindow.class.getResourceAsStream("form-window.html")) {
                    send(ex, 200, "text/html; charset=utf-8", in.readAllBytes());
                }
            } else if (what.equals("state") && ex.getRequestMethod().equals("GET")) {
                String q = Objects.toString(ex.getRequestURI().getQuery(), "");
                long since = q.startsWith("since=") ? Long.parseLong(q.substring(6).replaceAll("\\D", "0")) : 0;
                send(ex, 200, "application/json", JsonLite.write(s.waitForChange(since, 20_000)).getBytes(StandardCharsets.UTF_8));
            } else if (what.equals("event") && ex.getRequestMethod().equals("POST")) {
                if (!"1".equals(ex.getRequestHeaders().getFirst("X-OOA-Form"))) { send(ex, 403, "text/plain", new byte[0]); return; }
                Object body = JsonLite.parse(new String(ex.getRequestBody().readNBytes(1_000_000), StandardCharsets.UTF_8));
                if (body instanceof Map<?, ?> m) personEvent(s, (Map<String, Object>) m);
                send(ex, 200, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
            } else send(ex, 404, "text/plain", new byte[0]);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            send(ex, 400, "text/plain; charset=utf-8", String.valueOf(e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** A click, change, submit or close from the person: answers are kept, and the agent gets the event. */
    @SuppressWarnings("unchecked")
    static void personEvent(Session s, Map<String, Object> e) {
        String type = String.valueOf(e.get("type"));
        if (!Set.of("button", "change", "submit", "close").contains(type)) return;
        synchronized (s) {
            if (s.closed) return;
            if (e.get("values") instanceof Map<?, ?> v)
                for (var x : ((Map<String, Object>) v).entrySet()) if (FormsPackage.isInput(FormsPackage.find(s.form, x.getKey()))) s.values.put(x.getKey(), x.getValue());
            if (type.equals("close")) s.closed = true;
            s.changed();
        }
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("type", type);
        ev.put("element", e.get("element") == null ? "" : String.valueOf(e.get("element")));
        ev.put("value", e.get("element") == null ? null : s.values.get(String.valueOf(e.get("element"))));
        s.events.offer(ev);
        if (type.equals("close")) BY_TOKEN.remove(s.token);
    }

    private static void send(HttpExchange ex, int status, String type, byte[] b) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; frame-ancestors 'none'");
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }
}
