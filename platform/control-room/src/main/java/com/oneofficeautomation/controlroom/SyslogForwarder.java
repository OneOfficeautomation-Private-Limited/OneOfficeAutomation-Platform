package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.OoaException;

import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Forwards every audit entry to a SIEM over syslog (RFC 5424), UDP or TCP (RFC 6587 octet counting), when Settings
 * names a target (syslog_target host:port, syslog_protocol udp|tcp). Sending happens on its own thread with a bounded
 * queue, so a slow or absent collector never slows the Command Center; entries that do not fit are counted as dropped.
 */
final class SyslogForwarder {
    private final Platform platform;
    private final AtomicLong sent = new AtomicLong(), dropped = new AtomicLong(), failed = new AtomicLong();
    private final ExecutorService sender = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2000),
        r -> { Thread t = new Thread(r, "ooa-syslog"); t.setDaemon(true); return t; }, (r, ex) -> dropped.incrementAndGet());
    private static final String HOST = hostName();

    SyslogForwarder(Platform platform) { this.platform = platform; }

    /** Queues one audit entry (seq, ts, actor, action, object_type, object_id, details, result, ip) when a target is set. */
    void forward(Map<String, Object> entry) {
        String target = String.valueOf(platform.setting("syslog_target")).trim();
        if (target.isEmpty()) return;
        String protocol = String.valueOf(platform.setting("syslog_protocol"));
        String line = format(entry);
        sender.execute(() -> {
            try { send(target, protocol, line); sent.incrementAndGet(); }
            catch (Exception e) { failed.incrementAndGet(); }
        });
    }

    /** Sends one test message straight away and reports whether it left (UDP cannot confirm arrival). */
    Map<String, Object> test(String by) {
        String target = String.valueOf(platform.setting("syslog_target")).trim();
        if (target.isEmpty()) throw new OoaException("CR.BAD_INPUT", "Set the syslog target (host:port) first");
        try {
            send(target, String.valueOf(platform.setting("syslog_protocol")), format(Map.of("seq", 0, "ts", Instant.now().toString(), "actor", by, "action", "syslog.test",
                "object_type", "settings", "object_id", "", "details", "Test message from the Command Center", "result", "ok", "ip", "")));
            return Map.of("ok", true, "sent", sent.get(), "dropped", dropped.get(), "failed", failed.get());
        } catch (Exception e) {
            throw new OoaException("CR.SYSLOG_FAILED", "The syslog collector at " + target + " could not be reached: " + e.getMessage(), "Check the host, port, protocol and firewall");
        }
    }

    /** RFC 5424: facility 13 (log audit); severity notice (5) for ok, warning (4) otherwise. */
    static String format(Map<String, Object> e) {
        int pri = 13 * 8 + ("ok".equals(String.valueOf(e.get("result"))) ? 5 : 4);
        String sd = "[ooa@32473 seq=\"" + esc(e.get("seq")) + "\" actor=\"" + esc(e.get("actor")) + "\" action=\"" + esc(e.get("action")) + "\" object_type=\"" + esc(e.get("object_type"))
            + "\" object_id=\"" + esc(e.get("object_id")) + "\" result=\"" + esc(e.get("result")) + "\" ip=\"" + esc(e.get("ip")) + "\"]";
        String msg = e.get("details") == null ? "" : String.valueOf(e.get("details"));
        return "<" + pri + ">1 " + e.get("ts") + " " + HOST + " ooa-command-center - " + e.get("action") + " " + sd + (msg.isEmpty() ? "" : " ﻿" + msg);
    }

    private static String esc(Object v) {
        return v == null ? "" : String.valueOf(v).replace("\\", "\\\\").replace("\"", "\\\"").replace("]", "\\]");
    }

    private void send(String target, String protocol, String line) throws Exception {
        int colon = target.lastIndexOf(':');
        if (colon < 1) throw new IllegalArgumentException("syslog_target must be host:port");
        String host = target.substring(0, colon).replace("[", "").replace("]", "");
        NetGuard.check(platform.cr(), host, "The syslog collector");
        int port = Integer.parseInt(target.substring(colon + 1));
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        if ("tcp".equalsIgnoreCase(protocol)) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), 5000);
                OutputStream out = s.getOutputStream();
                out.write((bytes.length + " ").getBytes(StandardCharsets.US_ASCII));
                out.write(bytes);
                out.flush();
            }
        } else {
            if (bytes.length > 8000) bytes = java.util.Arrays.copyOf(bytes, 8000);
            try (DatagramSocket s = new DatagramSocket()) { s.send(new DatagramPacket(bytes, bytes.length, InetAddress.getByName(host), port)); }
        }
    }

    private static String hostName() {
        try { return InetAddress.getLocalHost().getHostName().replaceAll("[^A-Za-z0-9.-]", "-"); } catch (Exception e) { return "-"; }
    }
}
