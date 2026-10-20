package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ConnectorsTest.Call;
import com.oneofficeautomation.packages.ConnectorsTest.Fake;
import com.oneofficeautomation.sdk.ActionContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.oneofficeautomation.packages.ConnectorsTest.ok;
import static com.oneofficeautomation.packages.RestToolkitTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Kafka toolkit against a local imitation of a Confluent-compatible REST Proxy (v3 and v2 consumers). */
class KafkaPackageTest {
    static final String C = "/v3/clusters/lkc-abc12";
    final KafkaPackage pkg = new KafkaPackage();

    static String b64(String s) { return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }

    @Test void produceDiscoversClusterAndSendsKeyHeadersAndJson() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET /v3/clusters", c -> ok("{\"kind\":\"KafkaClusterList\",\"data\":[{\"cluster_id\":\"lkc-abc12\"}]}"))
             .on("POST " + C + "/topics/orders/records", c -> ok("{\"error_code\":200,\"cluster_id\":\"lkc-abc12\",\"topic_name\":\"orders\",\"partition_id\":2,\"offset\":41,\"timestamp\":\"2026-10-20T08:00:00.000Z\"}"))
             .on("POST " + C + "/topics/full/records", c -> ok("{\"error_code\":40301,\"message\":\"Not authorized to write\"}"));
            ActionContext ctx = ctx(PackagesTest.tmp());
            Map<String, Object> r = pkg.produce(ctx, f.url(), "orders", Map.of("id", "SO-1", "amount", new BigDecimal("12.5")), "SO-1", Map.of("source", "ooa"), "json", null, null, "KEY1", TOKEN, null);
            assertEquals(new BigDecimal(2), r.get("partition"));
            assertEquals(new BigDecimal(41), r.get("offset"));
            assertEquals("2026-10-20T08:00:00.000Z", r.get("timestamp"));
            Call c = f.last("POST", C + "/topics/orders/records");
            assertEquals("Basic " + Base64.getEncoder().encodeToString("KEY1:tok-123".getBytes()), c.auth());
            assertEquals("application/json", c.contentType());
            Map<String, Object> b = c.json();
            assertEquals(Map.of("type", "STRING", "data", "SO-1"), b.get("key"));
            assertEquals(Map.of("type", "JSON", "data", Map.of("id", "SO-1", "amount", new BigDecimal("12.5"))), b.get("value"));
            assertEquals(List.of(Map.of("name", "source", "value", b64("ooa"))), b.get("headers"));
            assertFalse(b.containsKey("partition_id"));

            pkg.produce(ctx, f.url(), "orders", "plain text", null, null, "text", 1, "lkc-abc12", null, null, TOKEN);
            Call c2 = f.last("POST", C + "/topics/orders/records");
            assertEquals("Bearer tok-123", c2.auth());
            assertEquals(Map.of("value", Map.of("type", "STRING", "data", "plain text"), "partition_id", new BigDecimal(1)), c2.json());
            assertEquals(1, f.calls.stream().filter(x -> x.path().equals("/v3/clusters")).count(), "given cluster id skips discovery");

            fails("KAFKA.PRODUCE_FAILED", () -> pkg.produce(ctx, f.url(), "full", "x", null, null, "text", null, "lkc-abc12", null, null, TOKEN));
            fails("KAFKA.BAD_TOPIC", () -> pkg.produce(ctx, f.url(), "../admin", "x", null, null, "text", null, "lkc-abc12", null, null, TOKEN));
            fails("KAFKA.NO_TOKEN", () -> pkg.produce(ctx, f.url(), "orders", "x", null, null, "text", null, "lkc-abc12", null, null, null));
        }
    }

    @Test void topicsListCreateAndInfo() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET " + C + "/topics", c -> c.query() != null && c.query().contains("page=2")
                    ? ok("{\"metadata\":{\"next\":null},\"data\":[{\"topic_name\":\"payments\",\"partitions_count\":3,\"replication_factor\":3,\"is_internal\":false}]}")
                    : ok("{\"metadata\":{\"next\":\"" + f.url() + C + "/topics?page=2\"},\"data\":[{\"topic_name\":\"orders\",\"partitions_count\":6,\"replication_factor\":3,\"is_internal\":false},"
                       + "{\"topic_name\":\"_schemas\",\"partitions_count\":1,\"replication_factor\":3,\"is_internal\":true}]}"))
             .on("POST " + C + "/topics", c -> c.text().contains("\"orders\"")
                    ? status(400, "{\"error_code\":40002,\"message\":\"Topic 'orders' already exists.\"}")
                    : status(201, "{\"topic_name\":\"invoices\"}"))
             .on("GET " + C + "/topics/orders", c -> ok("{\"topic_name\":\"orders\",\"partitions_count\":6,\"replication_factor\":3,\"is_internal\":false}"))
             .on("GET " + C + "/topics/orders/configs", c -> ok("{\"data\":[{\"name\":\"retention.ms\",\"value\":\"604800000\"},{\"name\":\"cleanup.policy\",\"value\":\"delete\"}]}"));
            ActionContext ctx = ctx(PackagesTest.tmp());
            Map<String, Object> l = pkg.listTopics(ctx, f.url(), false, "lkc-abc12", "k", TOKEN, null);
            assertEquals(List.of("orders", "payments"), l.get("names"), "internal topics hidden; next page followed");
            assertEquals(List.of(List.of("orders", new BigDecimal(6), new BigDecimal(3), false)), rows(l.get("table")).subList(0, 1));
            assertEquals(List.of("orders", "_schemas", "payments"), pkg.listTopics(ctx, f.url(), true, "lkc-abc12", "k", TOKEN, null).get("names"));

            assertEquals(true, pkg.createTopic(ctx, f.url(), "invoices", 3, 3, Map.of("retention.ms", 86400000), false, "lkc-abc12", "k", TOKEN, null).get("created"));
            assertEquals(Map.of("topic_name", "invoices", "partitions_count", new BigDecimal(3), "replication_factor", new BigDecimal(3),
                    "configs", List.of(Map.of("name", "retention.ms", "value", "86400000"))), f.last("POST", C + "/topics").json());
            assertEquals(false, pkg.createTopic(ctx, f.url(), "orders", 6, null, null, false, "lkc-abc12", "k", TOKEN, null).get("created"));
            fails("KAFKA.TOPIC_EXISTS", () -> pkg.createTopic(ctx, f.url(), "orders", 6, null, null, true, "lkc-abc12", "k", TOKEN, null));

            Map<String, Object> info = pkg.getTopic(ctx, f.url(), "orders", "lkc-abc12", "k", TOKEN, null);
            assertEquals(Map.of("topic", "orders", "partitions", new BigDecimal(6), "replication_factor", new BigDecimal(3), "internal", false), info.get("info"));
            assertEquals(Map.of("retention.ms", "604800000", "cleanup.policy", "delete"), info.get("settings"));
        }
    }

    @Test void readMessagesCreatesSubscribesPollsCommitsAndDeletes() throws Exception {
        try (Fake f = new Fake()) {
            AtomicInteger polls = new AtomicInteger();
            String inst = "/consumers/ooa-orders/instances/ooa-123";
            f.on("POST /consumers/ooa-orders", c -> ok("{\"instance_id\":\"ooa-123\",\"base_uri\":\"http://internal-host:8082" + inst + "\"}"))
             .on("POST " + inst + "/subscription", c -> status(204, ""))
             .on("GET " + inst + "/records", c -> switch (polls.incrementAndGet()) {
                 case 1 -> ok("[]");
                 case 2 -> ok("[{\"topic\":\"orders\",\"key\":\"SO-1\",\"value\":{\"amount\":5},\"partition\":0,\"offset\":10},"
                         + "{\"topic\":\"orders\",\"key\":null,\"value\":\"hello\",\"partition\":1,\"offset\":3}]");
                 default -> ok("[]");
             })
             .on("POST " + inst + "/offsets", c -> status(204, ""))
             .on("DELETE " + inst, c -> status(204, ""));
            ActionContext ctx = ctx(PackagesTest.tmp());
            Map<String, Object> r = pkg.read(ctx, f.url(), List.of("orders"), "ooa-orders", 100, 1, "json", "earliest", true, "k", TOKEN, null);
            assertEquals(new BigDecimal(2), r.get("count"));
            assertEquals(List.of("topic", "partition", "offset", "key", "value"), cols(r.get("table")));
            assertEquals(Arrays.asList("orders", new BigDecimal(0), new BigDecimal(10), "SO-1", "{\"amount\":5}"), rows(r.get("table")).get(0));
            assertEquals(Map.of("amount", new BigDecimal(5)), ((Map<?, ?>) ((List<?>) r.get("messages")).get(0)).get("value"));

            Call create = f.last("POST", "/consumers/ooa-orders");
            assertEquals("application/vnd.kafka.v2+json", create.contentType());
            Map<String, Object> cb = create.json();
            assertEquals("json", cb.get("format"));
            assertEquals("earliest", cb.get("auto.offset.reset"));
            assertEquals("false", cb.get("auto.commit.enable"));
            assertEquals(Map.of("topics", List.of("orders")), f.last("POST", inst + "/subscription").json());
            assertEquals("application/vnd.kafka.json.v2+json", f.last("GET", inst + "/records").header("Accept"));
            assertNotNull(f.last("POST", inst + "/offsets"), "read messages committed");
            List<String> order = f.calls.stream().map(c -> c.method() + " " + c.path()).toList();
            assertEquals("DELETE " + inst, order.get(order.size() - 1), "reader closed at the end, using the proxy address given, not the internal base_uri");
        }
    }

    @Test void readTextMessagesAndCloseReaderOnError() throws Exception {
        try (Fake f = new Fake()) {
            String inst = "/consumers/g1/instances/i1";
            f.on("POST /consumers/g1", c -> ok("{\"instance_id\":\"i1\"}"))
             .on("POST " + inst + "/subscription", c -> c.text().contains("missing") ? status(404, "{\"error_code\":40401,\"message\":\"Topic not found.\"}") : status(204, ""))
             .on("GET " + inst + "/records", c -> ok("[{\"topic\":\"logs\",\"key\":\"" + b64("k1") + "\",\"value\":\"" + b64("line one") + "\",\"partition\":0,\"offset\":0}]"))
             .on("POST " + inst + "/offsets", c -> status(204, ""))
             .on("DELETE " + inst, c -> status(204, ""));
            ActionContext ctx = ctx(PackagesTest.tmp());
            Map<String, Object> r = pkg.read(ctx, f.url(), List.of("logs"), "g1", 1, 1, "text", "latest", false, null, null, TOKEN);
            assertEquals(Arrays.asList("logs", new BigDecimal(0), new BigDecimal(0), "k1", "line one"), rows(r.get("table")).get(0));
            assertEquals("binary", f.last("POST", "/consumers/g1").json().get("format"));
            assertEquals("latest", f.last("POST", "/consumers/g1").json().get("auto.offset.reset"));
            assertEquals("application/vnd.kafka.binary.v2+json", f.last("GET", inst + "/records").header("Accept"));
            assertNull(f.last("POST", inst + "/offsets"), "nothing committed when mark_as_read is off");

            int deletes = (int) f.calls.stream().filter(c -> c.method().equals("DELETE")).count();
            fails("KAFKA.NOT_FOUND", () -> pkg.read(ctx, f.url(), List.of("missing"), "g1", 10, 1, "json", "earliest", true, null, null, TOKEN));
            assertEquals(deletes + 1, f.calls.stream().filter(c -> c.method().equals("DELETE")).count(), "reader closed even when reading fails");
            fails("KAFKA.BAD_GROUP", () -> pkg.read(ctx, f.url(), List.of("logs"), "bad group!", 10, 1, "json", "earliest", true, null, null, TOKEN));
        }
    }

    @Test void httpErrorsThroughAnAgentRun() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET " + C + "/topics/nope", c -> status(404, "{\"error_code\":40403,\"message\":\"This server does not host this topic-partition.\"}"))
             .on("GET " + C + "/topics/slow", c -> status(503, "{\"error_code\":50301,\"message\":\"Broker not available\"}"))
             .on("GET " + C + "/topics/locked", c -> status(401, "{\"error_code\":40101,\"message\":\"Unauthorized\"}"));
            String step = "[{\"id\":\"t\",\"action\":\"ooa.kafka/get_topic\",\"args\":{\"base_url\":\"" + f.url() + "\",\"topic\":\"T\",\"cluster_id\":\"lkc-abc12\",\"api_key\":\"KEY1\",\"api_secret\":\"$tok\"}}]";
            FlowRun nf = flow(PackagesTest.tmp(), "", step.replace("\"T\"", "\"nope\""));
            assertEquals("KAFKA.NOT_FOUND", nf.result().error().get("code"));
            assertTrue(String.valueOf(nf.result().error().get("message")).contains("does not host this topic"));
            assertFalse(nf.events().contains("tok-123"));
            FlowRun down = flow(PackagesTest.tmp(), "", step.replace("\"T\"", "\"slow\""));
            assertEquals("KAFKA.SERVER_ERROR", down.result().error().get("code"));
            assertEquals(true, down.result().error().get("retryable"));
            assertEquals("KAFKA.AUTH_FAILED", flow(PackagesTest.tmp(), "", step.replace("\"T\"", "\"locked\"")).result().error().get("code"));
        }
    }
}
