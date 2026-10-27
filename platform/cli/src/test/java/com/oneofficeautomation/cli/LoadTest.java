package com.oneofficeautomation.cli;

import com.oneofficeautomation.controlroom.*;
import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Load and soak (Q5): one Command Center over HTTP with many runners (heartbeats and long polls), a long work queue
 * and a large audit trail. Not part of the normal build: gradlew :cli:loadTest (-Pooa.load.runners=100
 * -Pooa.load.items=2000 -Pooa.load.audit=100000; -Pooa.test.db=... for SQL Server or PostgreSQL). The figures go to
 * the console and build/reports/load.txt.
 */
@Tag("load")
class LoadTest {
    static int prop(String k, int dflt) { return Integer.getInteger(k, dflt); }

    record Timing(String what, List<Long> ms) {
        long pct(double p) { List<Long> s = new ArrayList<>(ms); Collections.sort(s); return s.isEmpty() ? 0 : s.get((int) Math.min(s.size() - 1, Math.floor(p * s.size()))); }
        String line() { return String.format("%-34s n=%-7d p50=%5d ms  p95=%5d ms  p99=%5d ms  max=%5d ms", what, ms.size(), pct(.5), pct(.95), pct(.99), pct(1)); }
    }

    @SuppressWarnings("unchecked")
    @Test void runnersQueueAndAudit() throws Exception {
        int runners = prop("ooa.load.runners", 50), items = prop("ooa.load.items", 1000), auditRows = prop("ooa.load.audit", 50_000);
        Path data = Files.createTempDirectory("ooa-load");
        Db db = Db.embedded(data);
        ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        String pw = cr.bootstrap();
        String t = (String) cr.login("admin", pw, null).get("token");
        cr.changePassword(cr.session(t), pw, "Admin-Passw0rd-2026", t, null);
        ControlRoom.User admin = cr.session((String) cr.login("admin", "Admin-Passw0rd-2026", null).get("token"));
        ControlRoomServer server = new ControlRoomServer(cr, "127.0.0.1", 0);
        server.start();
        String base = "http://127.0.0.1:" + server.port() + "/api/";
        HttpClient http = HttpClient.newBuilder().executor(Executors.newVirtualThreadPerTaskExecutor()).connectTimeout(Duration.ofSeconds(10)).build();
        List<String> report = new ArrayList<>();
        report.add("Load test " + new Date() + ": " + runners + " runners, " + items + " work items, " + auditRows + " audit records, database " + db.info().get("engine"));
        try {
            // the agent, the runners in one pool, the queue
            cr.checkin(admin, "Load/Echo.flow.json", (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"echo\",\"name\":\"Echo\",\"description\":\"load\","
                + "\"variables\":[{\"name\":\"work_item\",\"type\":\"Dictionary\",\"direction\":\"input\"}],\"steps\":[]}"), "v1", true, null);
            List<String[]> devs = new ArrayList<>();
            for (int i = 0; i < runners; i++) {
                Map<String, Object> d = cr.registerDevice((String) cr.createRegistrationKey(admin, 1, false, null).get("key"), "LOAD-" + i, "load-" + i, "Windows 11", "0.2.0", null);
                devs.add(new String[] { (String) d.get("device_id"), (String) d.get("secret") });
            }
            String pool = (String) cr.platform.savePool(admin, null, "Load", "", devs.stream().map(d -> d[0]).toList(), null).get("id");
            String qid = (String) cr.platform.saveQueue(admin, null, Map.of("name", "Load", "bot_path", "Load/Echo.flow.json", "pool_id", pool, "max_attempts", 1), null).get("id");
            List<Object> batch = new ArrayList<>();
            long t0 = System.currentTimeMillis();
            for (int i = 0; i < items; i++) { batch.add(Map.of("n", i, "_reference", "L" + i)); if (batch.size() == 500) { cr.platform.addItems(admin, qid, batch, null); batch = new ArrayList<>(); } }
            if (!batch.isEmpty()) cr.platform.addItems(admin, qid, batch, null);
            report.add(String.format("Adding %d work items: %d ms", items, System.currentTimeMillis() - t0));

            // the runners: heartbeat, wait for work, report it done
            List<Long> beats = new CopyOnWriteArrayList<>(), polls = new CopyOnWriteArrayList<>(), finishes = new CopyOnWriteArrayList<>();
            AtomicInteger done = new AtomicInteger(), errors = new AtomicInteger();
            stop = false;
            ExecutorService pool2 = Executors.newVirtualThreadPerTaskExecutor();
            for (String[] d : devs) pool2.submit(() -> {
                while (!stop) {
                    try {
                        long a = System.nanoTime();
                        post(http, base + "agent/heartbeat", d, Map.of("agent_version", "0.2.0", "screen", "no_desktop"));
                        beats.add((System.nanoTime() - a) / 1_000_000);
                        a = System.nanoTime();
                        Map<String, Object> job = post(http, base + "agent/jobs/next?wait=5", d, Map.of());
                        if (job.get("job") instanceof Map<?, ?> jm && jm.get("run_id") instanceof String run) {
                            polls.add((System.nanoTime() - a) / 1_000_000);
                            a = System.nanoTime();
                            post(http, base + "agent/runs/" + run + "/events", d, Map.of("events", List.of(Map.of("type", "step.started", "ts", new Date().toString()))));
                            post(http, base + "agent/runs/" + run + "/finish", d, Map.of("result", Map.of("status", "success", "outputs", Map.of("ok", true))));
                            finishes.add((System.nanoTime() - a) / 1_000_000);
                            done.incrementAndGet();
                        }
                    } catch (Exception e) { errors.incrementAndGet(); }
                }
                return null;
            });
            cr.platform.setProcessing(admin, qid, true, null);
            long start = System.currentTimeMillis();
            long deadline = start + Math.max(120_000, items * 200L);
            while (System.currentTimeMillis() < deadline && ((Map<String, Object>) cr.platform.queue(admin, qid).get("counts")).get("success") instanceof Number n && n.longValue() < items) Thread.sleep(500);
            long took = System.currentTimeMillis() - start;
            stop = true;
            pool2.shutdown();
            pool2.awaitTermination(30, TimeUnit.SECONDS);
            long ok = ((Number) ((Map<String, Object>) cr.platform.queue(admin, qid).get("counts")).get("success")).longValue();
            report.add(String.format("Work queue drained: %d of %d items in %d ms (%.1f items/s), %d request errors", ok, items, took, ok * 1000.0 / Math.max(1, took), errors.get()));
            report.add(new Timing("Heartbeat", beats).line());
            report.add(new Timing("Long poll that brought work", polls).line());
            report.add(new Timing("Events + finish", finishes).line());
            if (ok < items) {
                report.add("Queue state: " + cr.platform.queue(admin, qid).get("counts"));
                for (Map<String, Object> it : db.query("SELECT id, run_id, status FROM queue_items WHERE queue_id = ? AND status <> 'success'", qid))
                    report.add("  stuck " + it + " run " + db.one("SELECT id, status, device_id, created, started, finished, queue_item_id FROM runs WHERE id = ?", it.get("run_id"))
                        + "; runs for the item: " + db.query("SELECT id, status FROM runs WHERE queue_item_id = ?", it.get("id")));
            }
            assertEquals(items, ok, "every item processed");

            // a large audit trail: writing, searching and verifying it
            t0 = System.currentTimeMillis();
            for (int i = 0; i < auditRows; i++) cr.audit.record("load", "load.write", "test", "x" + i, Map.of("i", i), "ok", null);
            long write = System.currentTimeMillis() - t0;
            t0 = System.currentTimeMillis();
            Map<String, Object> verify = cr.audit.verify();
            long ver = System.currentTimeMillis() - t0;
            report.add(String.format("Audit: %d records written in %d ms (%.0f/s); verify of the whole chain %d ms (%s)", auditRows, write, auditRows * 1000.0 / Math.max(1, write), ver, verify.get("valid")));
            assertEquals(true, verify.get("valid"));
        } finally {
            stop = true;
            server.stop();
            db.close();
            report.forEach(System.out::println);
            Path out = Path.of("build", "reports", "load.txt");
            Files.createDirectories(out.getParent());
            Files.write(out, report);
        }
    }

    static volatile boolean stop;

    @SuppressWarnings("unchecked")
    static Map<String, Object> post(HttpClient http, String url, String[] dev, Object body) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(40)).header("Content-Type", "application/json")
            .header("X-OOA-Device", dev[0]).header("X-OOA-Device-Secret", dev[1]).POST(HttpRequest.BodyPublishers.ofString(JsonLite.write(body))).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IllegalStateException(r.statusCode() + " " + r.body());
        Object v = JsonLite.parse(r.body());
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
