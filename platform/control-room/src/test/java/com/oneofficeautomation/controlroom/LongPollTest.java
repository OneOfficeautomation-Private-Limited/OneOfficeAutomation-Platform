package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Runners waiting for work (long poll): work arrives at once, a stop wakes the runner, many waiting runners block nothing. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LongPollTest {
    Db db; ControlRoom cr; ControlRoom.User admin; ControlRoomServer server; String base, deviceId, secret;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-longpoll");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        Map<String, Object> dev = cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), "R1", "r1", "Windows 11", "0.2.0", null);
        deviceId = (String) dev.get("device_id"); secret = (String) dev.get("secret");
        cr.checkin(admin, "Poll/Hello.flow.json", LicensingAndChecksTest.agent("hello"), "v1", true, null);
        server = new ControlRoomServer(cr, "127.0.0.1", 0);
        server.start();
        base = "http://127.0.0.1:" + server.port() + "/api/";
    }
    @AfterAll void stop() { server.stop(); cr.triggerPollers.stop(); db.close(); }

    @SuppressWarnings("unchecked")
    Map<String, Object> next(String query) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "agent/jobs/next" + query)).timeout(Duration.ofSeconds(40))
            .header("X-OOA-Device", deviceId).header("X-OOA-Device-Secret", secret).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        return (Map<String, Object>) JsonLite.parse(r.body());
    }

    @Test void aWaitingRunnerGetsWorkTheMomentItIsQueued() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        long t0 = System.currentTimeMillis();
        Future<Map<String, Object>> waiting = pool.submit(() -> next("?wait=20"));
        Thread.sleep(1500);
        assertFalse(waiting.isDone(), "the request is held while there is no work");
        String runId = (String) cr.startRun("admin", "admin", "Poll/Hello.flow.json", deviceId, Map.of(), "manual", null).get("run_id");
        Map<String, Object> r = waiting.get(5, TimeUnit.SECONDS);
        long took = System.currentTimeMillis() - t0;
        assertTrue(r.get("job") instanceof Map, String.valueOf(r));
        assertEquals(runId, ((Map<?, ?>) r.get("job")).get("run_id"));
        assertTrue(took < 4000, "answered as soon as the run was queued, after " + took + " ms");

        // a stop for the running run wakes the next waiting request with check: the runner sends a heartbeat at once
        Future<Map<String, Object>> again = pool.submit(() -> next("?wait=20"));
        Thread.sleep(800);
        cr.control(admin, runId, "stop", null);
        assertEquals(Map.of("job", false, "check", true), again.get(5, TimeUnit.SECONDS));
        pool.shutdownNow();
    }

    @Test void withoutWorkTheRequestEndsAfterTheWaitAndOldRunnersAreAnsweredAtOnce() throws Exception {
        long t0 = System.currentTimeMillis();
        assertEquals(Map.of("job", false), next("?wait=3"));
        long held = System.currentTimeMillis() - t0;
        assertTrue(held >= 2500 && held < 8000, "held about 3 s: " + held);
        t0 = System.currentTimeMillis();
        assertEquals(Map.of("job", false), next(""));
        assertTrue(System.currentTimeMillis() - t0 < 2000, "no wait parameter: answered at once");
    }

    @Test void manyWaitingRunnersDoNotBlockOtherRequests() throws Exception {
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<Map<String, Object>>> waits = new ArrayList<>();
        for (int i = 0; i < 30; i++) waits.add(pool.submit(() -> next("?wait=6")));
        Thread.sleep(500);
        long t0 = System.currentTimeMillis();
        HttpResponse<String> h = http.send(HttpRequest.newBuilder(URI.create(base + "health")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, h.statusCode());
        assertTrue(System.currentTimeMillis() - t0 < 2000, "the console still answers while 30 runners wait");
        for (Future<Map<String, Object>> f : waits) assertEquals(false, f.get(20, TimeUnit.SECONDS).get("job"));
        pool.shutdownNow();
    }
}
