package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.io.OutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Forwards every audit entry to a SIEM over syslog (RFC 5424), UDP or TCP (RFC 6587 octet counting), when Settings
 * names a target (syslog_target host:port, syslog_protocol udp|tcp). Sending happens on its own thread with a bounded
 * queue, so a slow or absent collector never slows One CommandCenter; entries that do not fit are counted as dropped.
 * Entries can also go as JSON to an HTTPS collector (siem_https_url, with a token from the vault in
 * siem_https_token_ref): a bearer token (Sentinel, Elastic, QRadar and most others) or Splunk's HTTP Event Collector.
 */
final class SyslogForwarder {
    private final Platform platform;
    private final AtomicLong sent = new AtomicLong(), dropped = new AtomicLong(), failed = new AtomicLong();
    private final ExecutorService sender = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2000),
        r -> { Thread t = new Thread(r, "ooa-syslog"); t.setDaemon(true); return t; }, (r, ex) -> dropped.incrementAndGet());
    private static final String HOST = hostName();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    // the token is read from the vault at most every 10 minutes: each read is itself an audit entry, which is forwarded too
    private volatile String tokenRef, token;
    private volatile Instant tokenRead = Instant.EPOCH;

    SyslogForwarder(Platform platform) { this.platform = platform; }

    /** Queues one audit entry (seq, ts, actor, action, object_type, object_id, details, result, ip) when a target is set. */
    void forward(Map<String, Object> entry) {
        String target = String.valueOf(platform.setting("syslog_target")).trim(), url = String.valueOf(platform.setting("siem_https_url")).trim();
        if (target.isEmpty() && url.isEmpty()) return;
        String protocol = String.valueOf(platform.setting("syslog_protocol"));
        String line = target.isEmpty() ? null : format(entry);
        Map<String, Object> copy = new LinkedHashMap<>(entry);
        sender.execute(() -> {
            if (line != null) try { send(target, protocol, line); sent.incrementAndGet(); } catch (Exception e) { failed.incrementAndGet(); }
            if (!url.isEmpty()) try { post(url, copy); sent.incrementAndGet(); } catch (Exception e) { failed.incrementAndGet(); }
        });
    }

    /** Sends one test entry to the HTTPS collector straight away and says whether it accepted it. */
    Map<String, Object> testHttps(String by) {
        String url = String.valueOf(platform.setting("siem_https_url")).trim();
        if (url.isEmpty()) throw new OoaException("CR.BAD_INPUT", "Set the SIEM address (https://...) first");
        tokenRead = Instant.EPOCH;   // a changed token is read again
        try {
            post(url, Map.of("seq", 0, "ts", Instant.now().toString(), "actor", by, "action", "siem.test", "object_type", "settings", "object_id", "",
                "details", "Test message from One CommandCenter", "result", "ok", "ip", ""));
            return Map.of("ok", true, "sent", sent.get(), "dropped", dropped.get(), "failed", failed.get());
        } catch (OoaException e) {
            throw e;
        } catch (Exception e) {
            throw new OoaException("CR.SIEM_FAILED", "The SIEM at " + url + " did not accept the entry: " + e.getMessage(), "Check the address, the token and the sign-in type");
        }
    }

    /** One audit entry as JSON; Splunk's collector wants it inside "event", with a source type. */
    private void post(String url, Map<String, Object> entry) throws Exception {
        NetGuard.check(platform.cr(), url, "The SIEM");
        boolean splunk = "splunk".equals(String.valueOf(platform.setting("siem_https_auth")));
        Map<String, Object> body = entry;
        if (splunk) {
            body = new LinkedHashMap<>();
            body.put("event", entry);
            body.put("source", "ooa-command-center");
            body.put("sourcetype", "ooa:audit");
            body.put("host", HOST);
        }
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JsonLite.write(body)));
        String t = token();
        if (t != null) req.header("Authorization", (splunk ? "Splunk " : "Bearer ") + t);
        HttpResponse<Void> res = http.send(req.build(), HttpResponse.BodyHandlers.discarding());
        if (res.statusCode() / 100 != 2) throw new IllegalStateException("it answered HTTP " + res.statusCode());
    }

    private String token() {
        String ref = String.valueOf(platform.setting("siem_https_token_ref")).trim();
        if (ref.isEmpty()) return null;
        if (!ref.equals(tokenRef) || tokenRead.isBefore(Instant.now().minus(Duration.ofMinutes(10)))) {
            token = platform.cr().systemCredential(ref);
            tokenRef = ref;
            tokenRead = Instant.now();
        }
        return token;
    }

    /** Sends one test message straight away and reports whether it left (UDP cannot confirm arrival). */
    Map<String, Object> test(String by) {
        String target = String.valueOf(platform.setting("syslog_target")).trim();
        if (target.isEmpty()) throw new OoaException("CR.BAD_INPUT", "Set the syslog target (host:port) first");
        try {
            send(target, String.valueOf(platform.setting("syslog_protocol")), format(Map.of("seq", 0, "ts", Instant.now().toString(), "actor", by, "action", "syslog.test",
                "object_type", "settings", "object_id", "", "details", "Test message from One CommandCenter", "result", "ok", "ip", "")));
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
