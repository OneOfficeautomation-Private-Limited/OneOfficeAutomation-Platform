package com.oneofficeautomation.agent;

import com.oneofficeautomation.sdk.JsonLite;

import javax.net.ssl.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * {@code ooa agent diagnose} (R15): checks, step by step, why a runner cannot reach or work with its Command Center:
 * config file, name lookup, the proxy, a TCP connection, the TLS certificate, the health address, the runner's own
 * credentials (a heartbeat), the clock, the screen, disk space and toolkits. Each line is PASS, WARN or FAIL with what to
 * do. Changes nothing.
 */
public final class Diagnose {
    private Diagnose() { }

    public record Line(String level, String what, String detail) {}

    @SuppressWarnings("unchecked")
    public static List<Line> run(Path configFile, Path workspace) {
        List<Line> out = new ArrayList<>();
        Map<String, Object> cfg;
        try { cfg = (Map<String, Object>) JsonLite.parse(Files.readString(configFile)); }
        catch (Exception e) { out.add(new Line("FAIL", "Runner settings", "cannot read " + configFile + ": register first (ooa agent register)")); return out; }
        out.add(new Line("PASS", "Runner settings", configFile + " (runner " + cfg.get("name") + ")"));
        String url = String.valueOf(cfg.get("url"));
        URI u = URI.create(url);
        Network net = Network.from(cfg);
        // name lookup
        try { out.add(new Line("PASS", "Name lookup", u.getHost() + " is " + InetAddress.getByName(u.getHost()).getHostAddress())); }
        catch (Exception e) { out.add(new Line(net.proxy().equals("none") ? "FAIL" : "WARN", "Name lookup", u.getHost() + " is unknown here" + (net.proxy().equals("none") ? ": check DNS or the address" : " (fine if only the proxy can resolve it)"))); }
        // proxy
        List<Proxy> proxies = net.proxy().equals("none") ? List.of(Proxy.NO_PROXY) : net.proxy().equals("system") ? ProxySelector.getDefault().select(u)
            : List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(URI.create(net.proxy()).getHost(), URI.create(net.proxy()).getPort())));
        out.add(new Line("PASS", "Proxy", net.proxy() + " -> " + (proxies.isEmpty() || proxies.get(0).type() == Proxy.Type.DIRECT ? "direct connection" : proxies.get(0).address())
            + (net.proxyUser() != null ? ", signs in as " + net.proxyUser() : "")));
        // TCP (direct only; through a proxy the HTTP checks below tell)
        int port = u.getPort() > 0 ? u.getPort() : url.startsWith("https") ? 443 : 80;
        if (proxies.isEmpty() || proxies.get(0).type() == Proxy.Type.DIRECT) {
            try (Socket s = new Socket()) { s.connect(new InetSocketAddress(u.getHost(), port), 5000); out.add(new Line("PASS", "Network", "port " + port + " on " + u.getHost() + " answers")); }
            catch (Exception e) { out.add(new Line("FAIL", "Network", "cannot connect to " + u.getHost() + ":" + port + " (" + e.getMessage() + "): check the firewall and that the Command Center runs")); return out; }
        }
        // TLS and health through the runner's own client settings
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
        net.apply(b, url);
        HttpClient http = b.build();
        Instant before = Instant.now();
        try {
            HttpResponse<String> h = http.send(HttpRequest.newBuilder(URI.create(url.replaceAll("/+$", "") + "/api/health")).timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString());
            if (url.startsWith("https")) out.add(new Line("PASS", "Certificate", "trusted"));
            out.add(new Line(h.statusCode() == 200 ? "PASS" : "FAIL", "Command Center", "health answered HTTP " + h.statusCode()));
            // clock: the Date header against this computer
            Optional<String> date = h.headers().firstValue("Date");
            if (date.isPresent()) {
                Instant theirs = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.parse(date.get(), Instant::from);
                long skew = Math.abs(Duration.between(before, theirs).toSeconds());
                out.add(new Line(skew <= 60 ? "PASS" : "WARN", "Clock", skew <= 60 ? "within a minute of the Command Center" : skew + " seconds apart: set both clocks from the same time server"));
            }
        } catch (javax.net.ssl.SSLHandshakeException e) {
            out.add(new Line("FAIL", "Certificate", "not trusted (" + e.getMessage() + "): register with --ca FILE, or install the company CA in Windows"));
            return out;
        } catch (Exception e) {
            out.add(new Line("FAIL", "Command Center", "no answer from " + url + " (" + e.getMessage() + ")" + (net.proxyUser() == null && !net.proxy().equals("none") ? "; if a proxy asks for a sign-in, set it with ooa agent network" : "")));
            return out;
        }
        // the runner's own credentials
        try {
            new AgentClient(url, (String) cfg.get("device_id"), (String) cfg.get("secret"), net).post("heartbeat", Map.of("agent_version", Agent.VERSION, "diagnose", true));
            out.add(new Line("PASS", "Runner sign-in", "the Command Center accepts this runner (" + cfg.get("device_id") + ")"));
        } catch (Exception e) {
            out.add(new Line("FAIL", "Runner sign-in", e.getMessage() + ": the runner may be deleted or disabled; register it again"));
        }
        // this computer
        String screen = DesktopSession.state();
        out.add(new Line(screen.equals("unlocked") ? "PASS" : "WARN", "Screen", screen + (screen.equals("unlocked") ? "" : ": agents that use the screen will not start here")));
        long free = workspace.toFile().getUsableSpace() > 0 ? workspace.toFile().getUsableSpace() / (1024 * 1024) : Path.of(System.getProperty("user.home")).toFile().getUsableSpace() / (1024 * 1024);
        out.add(new Line(free >= 1024 ? "PASS" : "WARN", "Free disk space", free / 1024 + " GB"));
        out.add(new Line("PASS", "Toolkits", Agent.TOOLKITS.size() + " installed"));
        return out;
    }
}
