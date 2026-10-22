package com.oneofficeautomation.packages.browser;

import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.JsonLite;

import java.io.*;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * The agent's side of the browser connector: finds the browsers whose extension is connected (registration files
 * that the native-messaging host writes to ~/.ooa/bridge), talks to their hosts over a local socket, and passes on
 * the extension's events (picked element, recorded step). One connection per browser, shared by the whole process.
 */
public final class ExtensionBridge {
    private ExtensionBridge() {}

    static Path dir = Path.of(System.getProperty("user.home"), ".ooa", "bridge");
    private static final Map<Path, Conn> CONNS = new ConcurrentHashMap<>();
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final AtomicLong IDS = new AtomicLong();

    record Listener(Predicate<Map<String, Object>> filter, BlockingQueue<Map<String, Object>> queue) {}

    /** A connected browser: which one it is and its host connection. */
    static final class Conn {
        final String browser; final Socket socket; final Writer w;
        final Map<String, CompletableFuture<Map<String, Object>>> waiting = new ConcurrentHashMap<>();
        Conn(String browser, Socket s) throws IOException {
            this.browser = browser; socket = s;
            w = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
        }
        boolean alive() { return !socket.isClosed(); }
    }

    /** The browsers connected now (edge, chrome), each once. */
    public static synchronized List<String> browsers() {
        List<String> out = new ArrayList<>();
        for (Conn c : connections()) if (!out.contains(c.browser)) out.add(c.browser);
        return out;
    }

    public static boolean available() { return !connections().isEmpty(); }

    @SuppressWarnings("unchecked")
    static synchronized List<Conn> connections() {
        List<Conn> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        List<Path> files;
        try (var s = Files.list(dir)) { files = s.filter(p -> p.toString().endsWith(".json")).toList(); }
        catch (IOException e) { return out; }
        for (Path f : files) {
            Conn c = CONNS.get(f);
            if (c != null && c.alive()) { out.add(c); continue; }
            CONNS.remove(f);
            try {
                Map<String, Object> reg = (Map<String, Object>) JsonLite.parse(Files.readString(f));
                long pid = ((Number) reg.get("pid")).longValue();
                if (!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) { Files.deleteIfExists(f); continue; }
                Socket s = new Socket(InetAddress.getLoopbackAddress(), ((Number) reg.get("port")).intValue());
                s.setTcpNoDelay(true);
                Conn conn = new Conn(String.valueOf(reg.get("browser")), s);
                BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                conn.w.write(JsonLite.write(Map.of("token", reg.get("token"))) + "\n"); conn.w.flush();
                s.setSoTimeout(5000);
                String ok = r.readLine();
                s.setSoTimeout(0);
                if (ok == null || !ok.contains("\"connected\":true")) { s.close(); continue; }
                Thread t = new Thread(() -> readLoop(conn, r), "ooa-extension-bridge-" + conn.browser);
                t.setDaemon(true);
                t.start();
                CONNS.put(f, conn);
                out.add(conn);
            } catch (IOException | RuntimeException e) { /* a host that just ended; skip it */ }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void readLoop(Conn c, BufferedReader r) {
        try {
            String line;
            while ((line = r.readLine()) != null) {
                Map<String, Object> m = (Map<String, Object>) JsonLite.parse(line);
                if (m.get("event") == null && m.get("id") != null) {
                    CompletableFuture<Map<String, Object>> f = c.waiting.remove(String.valueOf(m.get("id")));
                    if (f != null) f.complete(m);
                } else if (m.get("event") != null) {
                    for (Listener l : LISTENERS) if (l.filter().test(m)) l.queue().offer(m);
                }
            }
        } catch (IOException | RuntimeException ignored) {
        } finally {
            try { c.socket.close(); } catch (IOException ignored) { }
            for (CompletableFuture<Map<String, Object>> f : c.waiting.values()) f.complete(Map.of("ok", false, "code", "EXT.DISCONNECTED", "error", "The browser closed or the connector stopped"));
        }
    }

    static Conn conn(String browser) {
        List<Conn> all = connections();
        for (Conn c : all) if (browser == null || browser.isBlank() || browser.equalsIgnoreCase(c.browser)) return c;
        throw new ActionException("BROWSER.CONNECTOR_NOT_CONNECTED", (browser == null || browser.isBlank() ? "No browser" : "The " + browser + " browser") + " is not connected to ONEOFFICEAUTOMATION",
                "Open the browser with the ONEOFFICEAUTOMATION Browser Connector extension loaded (run: ooa browser-connector install), or use a browser the agent starts itself.", true);
    }

    /** Sends one request to the browser's extension and waits for its answer (the result, or an ActionException). */
    public static Object call(String browser, String op, Map<String, Object> args, long timeoutMs) {
        Conn c = conn(browser);
        String id = "r" + IDS.incrementAndGet();
        CompletableFuture<Map<String, Object>> f = new CompletableFuture<>();
        c.waiting.put(id, f);
        try {
            synchronized (c) { c.w.write(JsonLite.write(Map.of("id", id, "op", op, "args", args == null ? Map.of() : args)) + "\n"); c.w.flush(); }
            Map<String, Object> m = f.get(Math.max(1000, timeoutMs), TimeUnit.MILLISECONDS);
            if (Boolean.TRUE.equals(m.get("ok"))) return m.get("result");
            String code = String.valueOf(m.getOrDefault("code", "EXT.FAILED"));
            throw new ActionException(code.startsWith("BROWSER.") ? code : "BROWSER." + code.replace("EXT.", "CONNECTOR_"), String.valueOf(m.get("error")),
                    null, code.equals("BROWSER.OBJECT_NOT_FOUND"));
        } catch (TimeoutException e) {
            throw new ActionException("BROWSER.TIMEOUT", "The browser did not answer '" + op + "' within " + timeoutMs + " ms", "Raise the time-out, or check the browser is not showing a dialog.", true);
        } catch (IOException e) {
            throw new ActionException("BROWSER.CONNECTOR_NOT_CONNECTED", "Lost the connection to the browser: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ActionException("RUN.CANCELLED", "Interrupted");
        } catch (ExecutionException e) {
            throw new ActionException("BROWSER.ERROR", String.valueOf(e.getCause()));
        } finally { c.waiting.remove(id); }
    }

    /** Every tab of every connected browser: tab_id, window_id, title, url, active, browser. */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> tabs() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String b : browsers()) {
            try { for (Object o : (List<Object>) call(b, "list_tabs", Map.of(), 5000)) out.add(new LinkedHashMap<>((Map<String, Object>) o)); }
            catch (ActionException ignored) { }
        }
        return out;
    }

    /** Starts listening for events (before sending the request that causes them); close it when done. */
    public static Subscription subscribe(Predicate<Map<String, Object>> filter) {
        Listener l = new Listener(filter, new LinkedBlockingQueue<>());
        LISTENERS.add(l);
        return new Subscription(l);
    }

    public static final class Subscription implements AutoCloseable {
        private final Listener l;
        Subscription(Listener l) { this.l = l; }
        /** The next event, or null after the time-out. */
        public Map<String, Object> next(long timeoutMs) throws InterruptedException { return l.queue().poll(timeoutMs, TimeUnit.MILLISECONDS); }
        @Override public void close() { LISTENERS.remove(l); }
    }

    /** A browser tab driven through the extension, kept as a run session like a Playwright one. */
    public static final class TabSession implements AutoCloseable {
        final String browser; long tabId;
        /** True for a window this session opened: Close browser closes it; the end of the run leaves it open. */
        boolean own;
        TabSession(String browser, long tabId, boolean own) { this.browser = browser; this.tabId = tabId; this.own = own; }
        Map<String, Object> tab() { return new LinkedHashMap<>(Map.of("tab_id", tabId)); }
        @Override public void close() { /* the window stays open after the run, as with any browser the person uses */ }
    }
}
