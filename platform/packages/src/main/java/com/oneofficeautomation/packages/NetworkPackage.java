package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.*;
import java.util.Map;

@OoaPackage(id = "ooa.network", name = "Network", version = "1.0.0", description = "Check whether hosts and ports are reachable.")
public final class NetworkPackage {

    @Action(id = "ping", name = "Ping", category = "Network", idempotent = true, capabilities = "network", description = "Check whether a host answers, and how fast.")
    @Output(name = "reachable", type = OoaType.BOOLEAN, description = "True when the host answered.")
    @Output(name = "response_ms", type = OoaType.NUMBER, description = "Round-trip time in milliseconds.")
    public Map<String, Object> ping(ActionContext ctx,
            @Input(name = "host", type = OoaType.STRING, description = "Host name or IP address.") String host,
            @Input(name = "timeout_ms", type = OoaType.NUMBER, required = false, defaultValue = "3000", description = "Timeout in milliseconds.") int timeout) {
        long t0 = System.nanoTime();
        boolean ok;
        try { ok = InetAddress.getByName(host).isReachable(timeout); }
        catch (UnknownHostException e) { throw new ActionException("NETWORK.UNKNOWN_HOST", "Unknown host '" + host + "'", "Check the host name and DNS."); }
        catch (IOException e) { ok = false; }
        return Out.of("reachable", ok, "response_ms", BigDecimal.valueOf((System.nanoTime() - t0) / 1_000_000));
    }

    @Action(id = "check_port", name = "Check port", category = "Network", idempotent = true, capabilities = "network",
            description = "Check whether a TCP port accepts connections, e.g. a database on port 1433.")
    @Output(name = "open", type = OoaType.BOOLEAN, description = "True when the port accepted a connection.")
    public Map<String, Object> checkPort(ActionContext ctx,
            @Input(name = "host", type = OoaType.STRING, description = "Host name or IP address.") String host,
            @Input(name = "port", type = OoaType.NUMBER, description = "TCP port.") int port,
            @Input(name = "timeout_ms", type = OoaType.NUMBER, required = false, defaultValue = "3000", description = "Timeout in milliseconds.") int timeout) {
        try (Socket s = new Socket()) { s.connect(new InetSocketAddress(host, port), timeout); return Map.of("open", true); }
        catch (IOException e) { return Map.of("open", false); }
    }

    @Condition(id = "host_reachable", name = "Host is reachable", description = "True when the host answers a ping within the timeout.")
    public boolean reachable(ActionContext ctx,
            @Input(name = "host", type = OoaType.STRING, description = "Host name or IP address.") String host,
            @Input(name = "timeout_ms", type = OoaType.NUMBER, required = false, defaultValue = "3000", description = "Timeout in milliseconds.") int timeout) {
        try { return InetAddress.getByName(host).isReachable(timeout); } catch (IOException e) { return false; }
    }

    @Condition(id = "port_open", name = "Port is open", description = "True when a TCP port accepts connections.")
    public boolean portOpen(ActionContext ctx,
            @Input(name = "host", type = OoaType.STRING, description = "Host name or IP address.") String host,
            @Input(name = "port", type = OoaType.NUMBER, description = "TCP port.") int port,
            @Input(name = "timeout_ms", type = OoaType.NUMBER, required = false, defaultValue = "3000", description = "Timeout in milliseconds.") int timeout) {
        try (Socket s = new Socket()) { s.connect(new InetSocketAddress(host, port), timeout); return true; } catch (IOException e) { return false; }
    }
}
