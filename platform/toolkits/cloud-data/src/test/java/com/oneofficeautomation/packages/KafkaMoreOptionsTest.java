package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ConnectorsTest.Fake;
import com.oneofficeautomation.sdk.ActionContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static com.oneofficeautomation.packages.ConnectorsTest.ok;
import static com.oneofficeautomation.packages.RestToolkitTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Kafka options added from the gap list: commit_offsets against a fake REST Proxy (v2 consumers). */
class KafkaMoreOptionsTest {
    final KafkaPackage pkg = new KafkaPackage();

    @Test void commitOffsetsCreatesReaderCommitsAndCloses() throws Exception {
        try (Fake f = new Fake()) {
            String inst = "/consumers/ooa-orders/instances/i9";
            f.on("POST /consumers/ooa-orders", c -> ok("{\"instance_id\":\"i9\"}"))
             .on("POST " + inst + "/offsets", c -> status(204, ""))
             .on("DELETE " + inst, c -> status(204, ""));
            ActionContext ctx = ctx(PackagesTest.tmp());
            Map<String, Object> r = pkg.commitOffsets(ctx, f.url(), "ooa-orders", "orders", 2, 41L, "k", TOKEN, null);
            assertEquals(new BigDecimal(42), r.get("next_offset"));
            assertEquals("false", f.last("POST", "/consumers/ooa-orders").json().get("auto.commit.enable"));
            var commit = f.last("POST", inst + "/offsets");
            assertEquals("application/vnd.kafka.v2+json", commit.contentType());
            assertEquals(Map.of("offsets", List.of(Map.of("topic", "orders", "partition", new BigDecimal(2), "offset", new BigDecimal(41)))), commit.json());
            List<String> order = f.calls.stream().map(c -> c.method() + " " + c.path()).toList();
            assertEquals(List.of("POST /consumers/ooa-orders", "POST " + inst + "/offsets", "DELETE " + inst), order);
        }
    }

    @Test void commitOffsetsClosesReaderOnErrorAndValidatesInputs() throws Exception {
        try (Fake f = new Fake()) {
            String inst = "/consumers/g1/instances/i1";
            f.on("POST /consumers/g1", c -> ok("{\"instance_id\":\"i1\"}"))
             .on("POST " + inst + "/offsets", c -> status(404, "{\"error_code\":40401,\"message\":\"Topic not found.\"}"))
             .on("DELETE " + inst, c -> status(204, ""));
            ActionContext ctx = ctx(PackagesTest.tmp());
            fails("KAFKA.NOT_FOUND", () -> pkg.commitOffsets(ctx, f.url(), "g1", "missing", 0, 5L, null, null, TOKEN));
            assertNotNull(f.last("DELETE", inst), "reader closed even when the commit fails");

            int before = f.calls.size();
            fails("KAFKA.BAD_OFFSET", () -> pkg.commitOffsets(ctx, f.url(), "g1", "orders", 0, -1L, null, null, TOKEN));
            fails("KAFKA.BAD_PARTITION", () -> pkg.commitOffsets(ctx, f.url(), "g1", "orders", null, 3L, null, null, TOKEN));
            fails("KAFKA.BAD_GROUP", () -> pkg.commitOffsets(ctx, f.url(), "bad group!", "orders", 0, 3L, null, null, TOKEN));
            fails("KAFKA.BAD_TOPIC", () -> pkg.commitOffsets(ctx, f.url(), "g1", "../x", 0, 3L, null, null, TOKEN));
            assertEquals(before, f.calls.size(), "bad inputs are refused before any request");
        }
    }
}
