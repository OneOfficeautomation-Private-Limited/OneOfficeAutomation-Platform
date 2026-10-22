package com.oneofficeautomation.cli;

import com.oneofficeautomation.sdk.JsonLite;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The browser connector's native-messaging host ({@code ooa native-host}). The browser starts it for the extension;
 * it speaks the native-messaging protocol on stdin/stdout (a 4-byte little-endian length, then UTF-8 JSON) and relays
 * requests from agents and Agent Studio on this computer, which connect to a local socket.
 *
 * <p>Registration: once the extension says hello, the host writes {@code ~/.ooa/bridge/<browser>-<pid>.json} with its
 * port and a random token. Clients connect to 127.0.0.1:port, send one line {"token": "..."} and then one JSON object
 * per line: requests {"id", "op", "args"} get the extension's response with the same id; events the extension sends
 * by itself (picked element, recorded step, recording stopped) go to every connected client. Nothing but protocol
 * is ever written to stdout.
 */
public final class NativeHost {
    static final int MAX_MESSAGE = 64 * 1024 * 1024;

    private final InputStream in;
    private final OutputStream out;
    private final Path dir;
    private final List<Client> clients = new CopyOnWriteArrayList<>();
    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private final String token;
    private volatile Path registration;
    private ServerSocket server;

    record Pending(Client client, Object clientId) {}

    NativeHost(InputStream in, OutputStream out, Path dir) {
        this.in = in; this.out = out; this.dir = dir;
        byte[] t = new byte[24];
        new SecureRandom().nextBytes(t);
        this.token = HexFormat.of().formatHex(t);
    }

    public static int main(String[] args) {
        Path dir = Path.of(System.getProperty("user.home"), ".ooa", "bridge");
        try { return new NativeHost(System.in, System.out, dir).run(); }
        catch (IOException e) { System.err.println("native host: " + e.getMessage()); return 1; }
    }

    int run() throws IOException {
        Files.createDirectories(dir);
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread accept = new Thread(this::acceptLoop, "ooa-native-host-accept");
        accept.setDaemon(true);
        accept.start();
        try {
            while (true) {
                String msg = readMessage(in);
                if (msg == null) break;   // the browser closed the connection
                fromExtension(msg);
            }
        } finally {
            if (registration != null) Files.deleteIfExists(registration);
            try { server.close(); } catch (IOException ignored) { }
            for (Client c : clients) c.close();
        }
        return 0;
    }

    /** Reads one native-messaging message; null at the end of the stream. */
    static String readMessage(InputStream in) throws IOException {
        byte[] len = in.readNBytes(4);
        if (len.length < 4) return null;
        int n = ByteBuffer.wrap(len).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (n < 0 || n > MAX_MESSAGE) throw new IOException("Message too large: " + n);
        byte[] body = in.readNBytes(n);
        if (body.length < n) return null;
        return new String(body, StandardCharsets.UTF_8);
    }

    static void writeMessage(OutputStream out, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(body.length).array());
        out.write(body);
        out.flush();
    }

    @SuppressWarnings("unchecked")
    void fromExtension(String text) throws IOException {
        Object parsed;
        try { parsed = JsonLite.parse(text); } catch (RuntimeException e) { return; }
        if (!(parsed instanceof Map<?, ?> m)) return;
        Map<String, Object> msg = new LinkedHashMap<>((Map<String, Object>) m);
        if ("hello".equals(msg.get("event"))) register(String.valueOf(msg.getOrDefault("browser", "browser")), String.valueOf(msg.getOrDefault("version", "")));
        Object id = msg.get("id");
        if (id != null && msg.get("event") == null) {
            Pending p = pending.remove(((Number) id).longValue());
            if (p == null) return;
            msg.put("id", p.clientId());
            p.client().send(JsonLite.write(msg));
            return;
        }
        for (Client c : clients) c.send(JsonLite.write(msg));
    }

    /** Writes the registration file (port and token) that agents look for. Only this user can read the folder. */
    void register(String browser, String version) throws IOException {
        Path f = dir.resolve(browser.replaceAll("[^a-z0-9_-]", "") + "-" + ProcessHandle.current().pid() + ".json");
        Map<String, Object> reg = new LinkedHashMap<>();
        reg.put("browser", browser);
        reg.put("port", server.getLocalPort());
        reg.put("token", token);
        reg.put("pid", ProcessHandle.current().pid());
        reg.put("extension_version", version);
        reg.put("started", java.time.Instant.now().toString());
        Path tmp = Files.createTempFile(dir, "reg", ".tmp");
        Files.writeString(tmp, JsonLite.write(reg), StandardCharsets.UTF_8);
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        if (registration != null && !registration.equals(f)) Files.deleteIfExists(registration);
        registration = f;
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket s = server.accept();
                Thread t = new Thread(() -> serve(s), "ooa-native-host-client");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) { return; }
        }
    }

    @SuppressWarnings("unchecked")
    private void serve(Socket s) {
        Client c = null;
        try (s) {
            s.setTcpNoDelay(true);
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String hello = r.readLine();
            Object h = hello == null ? null : JsonLite.parse(hello);
            if (!(h instanceof Map<?, ?> hm) || !token.equals(hm.get("token"))) return;   // not one of ours
            c = new Client(s);
            clients.add(c);
            c.send("{\"ok\":true,\"connected\":true}");
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                Map<String, Object> req = new LinkedHashMap<>((Map<String, Object>) JsonLite.parse(line));
                long hostId = ids.incrementAndGet();
                pending.put(hostId, new Pending(c, req.get("id")));
                req.put("id", hostId);
                synchronized (out) { writeMessage(out, JsonLite.write(req)); }
            }
        } catch (IOException | RuntimeException e) {
            // the client went away or sent something unreadable
        } finally {
            if (c != null) { clients.remove(c); Client gone = c; pending.values().removeIf(p -> p.client() == gone); }
        }
    }

    static final class Client {
        private final Socket socket;
        private final Writer w;
        Client(Socket s) throws IOException { socket = s; w = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8)); }
        synchronized void send(String line) { try { w.write(line); w.write('\n'); w.flush(); } catch (IOException e) { close(); } }
        void close() { try { socket.close(); } catch (IOException ignored) { } }
    }
}
