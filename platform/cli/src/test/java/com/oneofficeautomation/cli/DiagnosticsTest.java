package com.oneofficeautomation.cli;

import com.oneofficeautomation.agent.Agent;
import com.oneofficeautomation.agent.Diagnose;
import com.oneofficeautomation.controlroom.*;
import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** ooa check (I10) and ooa agent diagnose (R15) name the problem a person would otherwise have to find. */
class DiagnosticsTest {

    static String level(List<Diagnose.Line> lines, String what) { return lines.stream().filter(l -> l.what().equals(what)).map(Diagnose.Line::level).findFirst().orElse("missing"); }

    @Test void diagnoseFindsWhereTheRunnerGetsStuck() throws Exception {
        Path root = Files.createTempDirectory("ooa-diag"), data = Files.createDirectories(root.resolve("data"));
        Db db = Db.embedded(data);
        ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        String pw = cr.bootstrap();
        String t = (String) cr.login("admin", pw, null).get("token");
        cr.changePassword(cr.session(t), pw, "Admin-Passw0rd-2026", t, null);
        ControlRoom.User admin = cr.session((String) cr.login("admin", "Admin-Passw0rd-2026", null).get("token"));
        ControlRoomServer server = new ControlRoomServer(cr, "127.0.0.1", 0);
        server.start();
        try {
            Path conf = root.resolve("agent").resolve("agent.json");
            Map<String, Object> r = Agent.register("http://127.0.0.1:" + server.port(), (String) cr.createRegistrationKey(admin, 1, null).get("key"), "R-diag", conf);
            List<Diagnose.Line> ok = Diagnose.run(conf, root);
            assertEquals("PASS", level(ok, "Command Center"), ok.toString());
            assertEquals("PASS", level(ok, "Runner sign-in"), ok.toString());
            assertEquals("PASS", level(ok, "Clock"), ok.toString());

            cr.deleteDevice(admin, (String) r.get("device_id"), null);
            assertEquals("FAIL", level(Diagnose.run(conf, root), "Runner sign-in"), "a deleted runner is told to register again");

            @SuppressWarnings("unchecked") Map<String, Object> cfg = new LinkedHashMap<>((Map<String, Object>) JsonLite.parse(Files.readString(conf)));
            int closed; try (ServerSocket s = new ServerSocket(0)) { closed = s.getLocalPort(); }
            cfg.put("url", "http://127.0.0.1:" + closed);
            Files.writeString(conf, JsonLite.write(cfg));
            assertEquals("FAIL", level(Diagnose.run(conf, root), "Network"), "nothing listens there");
            assertEquals("FAIL", level(Diagnose.run(root.resolve("none.json"), root), "Runner settings"));
        } finally { server.stop(); db.close(); }
    }

    @Test void checkReportsABusyPortAndAnUnreachableDatabase() throws Exception {
        Path data = Files.createTempDirectory("ooa-check");
        try (ServerSocket busy = new ServerSocket(0)) {
            List<CheckCommand.Result> r = CheckCommand.checks(data, busy.getLocalPort());
            assertEquals("FAIL", r.stream().filter(x -> x.what().startsWith("Port")).findFirst().orElseThrow().level());
        }
        Files.createDirectories(DbConfig.defaultFile(data).getParent());
        Files.writeString(DbConfig.defaultFile(data), String.join("\n", "engine=sqlserver", "host=127.0.0.1", "port=1", "database=nothing", "user=nobody", ""));
        List<CheckCommand.Result> r = CheckCommand.checks(data, 0);
        assertEquals("FAIL", r.stream().filter(x -> x.what().equals("Database")).findFirst().orElseThrow().level(), r.toString());
    }
}
