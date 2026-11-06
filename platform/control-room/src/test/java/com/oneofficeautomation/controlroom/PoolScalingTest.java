package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** G52: a pool's size follows its work; the cloud is told through a signed webhook, at most once per cooldown. */
class PoolScalingTest {
    @SuppressWarnings("unchecked")
    static Map<String, Object> agent(String id) {
        return (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"" + id + "\",\"name\":\"" + id + "\",\"description\":\"t\",\"variables\":[],\"steps\":[]}");
    }

    @Test void thePoolGrowsWithItsWorkAndFromZero() throws Exception {
        Path data = Files.createTempDirectory("ooa-g52");
        Db db = Db.embedded(data);
        List<String[]> calls = new CopyOnWriteArrayList<>();
        HttpServer hook = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        hook.createContext("/scale", ex -> {
            calls.add(new String[]{new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), ex.getRequestHeaders().getFirst("X-OOA-Signature")});
            ex.sendResponseHeaders(204, -1); ex.close();
        });
        hook.start();
        try {
            ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
            ControlRoom.User admin = RepositoryLifecycleTest.boot(cr);
            String dev = (String) cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), "VM-1", "vm-1", "Windows 11", "0.2.0", null).get("device_id");
            String pool = (String) cr.platform.savePool(admin, null, "Cloud", "Scaled", List.of(dev), null, null, null).get("id");
            cr.createLocker(admin, "ops", "Operations", null, null);
            cr.setCredential(admin, "ops", "scale", "secret", "hook-secret", null);
            String url = "http://127.0.0.1:" + hook.getAddress().getPort() + "/scale";

            assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> cr.poolScaling.put(admin, pool, Map.of("enabled", true, "min", 3, "max", 1, "provider", "webhook", "url", url), null)).code());
            assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> cr.poolScaling.put(admin, pool, Map.of("enabled", true, "provider", "webhook", "url", "http://example.com/x"), null)).code(),
                    "plain http only to this computer");
            cr.poolScaling.put(admin, pool, Map.of("enabled", true, "min", 0, "max", 5, "runs_per_runner", 2, "cooldown_minutes", 1, "provider", "webhook", "url", url, "secret_ref", "ops/scale/secret"), null);

            // no runner online: a run for the pool is refused, and that counts as demand
            db.update("UPDATE devices SET last_seen = NULL WHERE id = ?", dev);
            cr.checkin(admin, "S/A.flow.json", agent("a"), "v1", true, null);
            assertEquals("CR.NO_RUNNER", assertThrows(OoaException.class, () -> cr.startRun("admin", "admin", "S/A.flow.json", null, pool, Map.of(), "manual", null, 0, null, null, null)).code());
            assertEquals(1, ((Number) ((Map<?, ?>) cr.poolScaling.get(admin, pool).get("now")).get("desired")).intValue(), "scaled up from zero");
            cr.poolScaling.tick();
            assertEquals(1, calls.size());
            String body = calls.get(0)[0];
            Map<?, ?> sent = (Map<?, ?>) JsonLite.parse(body);
            assertEquals(pool, sent.get("pool"));
            assertEquals(1, ((Number) sent.get("desired")).intValue());
            assertEquals("sha256=" + PoolScaling.hmacHex("hook-secret", body), calls.get(0)[1], "signed with the vault secret");

            // the same size again: nothing sent; queued work on the pool's runner: 5 runs, 2 per machine -> 3 machines, after the cooldown
            cr.poolScaling.tick();
            assertEquals(1, calls.size());
            for (int i = 0; i < 5; i++) cr.startRun("admin", "admin", "S/A.flow.json", dev, Map.of(), "manual", null);
            @SuppressWarnings("unchecked") Map<String, Object> now = (Map<String, Object>) cr.poolScaling.get(admin, pool).get("now");
            assertEquals(5L, ((Number) now.get("queued")).longValue());
            assertEquals(3, ((Number) now.get("desired")).intValue());
            cr.poolScaling.tick();
            assertEquals(1, calls.size(), "still within the cooldown");
            cr.poolScaling.last.put(pool, new Object[]{1, ControlRoom.now().minusSeconds(120)});
            cr.poolScaling.tick();
            assertEquals(2, calls.size());
            assertTrue(calls.get(1)[0].contains("\"desired\":3"), calls.get(1)[0]);
            assertEquals(1L, cr.count("SELECT COUNT(*) AS n FROM audit WHERE action = 'pool.scaling_set'"));
        } finally { hook.stop(0); db.close(); }
    }
}
