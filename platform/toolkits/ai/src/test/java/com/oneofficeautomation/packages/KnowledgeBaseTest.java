package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionContext;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** G71: a knowledge index published to a knowledge base and asked by name, fetched again only when it changed; web pages as a source. */
class KnowledgeBaseTest {
    @TempDir Path w;

    @Test @SuppressWarnings("unchecked")
    void publishedOnceAskedByNameAndFetchedOnlyWhenChanged() throws Exception {
        Map<String, Object> stored = new HashMap<>();
        List<String> ops = new ArrayList<>();
        Function<Map<String, Object>, Map<String, Object>> server = req -> {
            ops.add(req.get("op") + (req.get("sha256") != null && !"".equals(req.get("sha256")) ? ":cached" : ""));
            if ("publish".equals(req.get("op"))) { stored.put("content", req.get("content")); stored.put("sha", "sha-" + stored.size() + "-" + ((String) req.get("content")).length()); return Map.of("ok", true); }
            if (stored.get("sha").equals(req.get("sha256"))) return Map.of("not_modified", true, "sha256", stored.get("sha"));
            return Map.of("sha256", stored.get("sha"), "content", stored.get("content"));
        };
        HttpServer web = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        web.createContext("/leave", ex -> {
            byte[] b = "<html><body><h1>Leave</h1><p>Staff get 24 holiday days a year.</p><script>x()</script></body></html>".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/html"); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close();
        });
        web.start();
        try {
            ActionContext ctx = PackagesTestSupport.ctx(w, Map.of("knowledge_bases", server));
            KnowledgePackage k = new KnowledgePackage();
            Map<String, Object> built = k.buildIndex(ctx, null, List.of(Map.of("source", "travel", "text", "Book travel through the portal.")), null, null, null, null, null, null,
                1000, 150, false, "HR policies", List.of("http://127.0.0.1:" + web.getAddress().getPort() + "/leave", "http://127.0.0.1:1/none"));
            assertEquals("2", String.valueOf(built.get("documents")));
            assertEquals(1, ((List<?>) built.get("skipped")).size(), "a page that cannot be read is listed, not fatal");
            assertFalse(Files.exists(w.resolve("knowledge-bases")), "nothing kept locally when One CommandCenter keeps it");
            List<Object> hits = (List<Object>) k.search(ctx, null, "holiday days", 3, null, null, "HR policies").get("results");
            assertTrue(String.valueOf(hits.get(0)).contains("24 holiday days"), String.valueOf(hits));
            assertFalse(String.valueOf(hits).contains("x()"), "scripts are left out of pages");
            k.search(ctx, null, "portal", 3, null, null, "HR policies");
            assertEquals(List.of("publish", "fetch", "fetch:cached"), ops, "fetched once, then only asked whether it changed");
        } finally { web.stop(0); }
    }

    @Test @SuppressWarnings("unchecked")
    void inStudioTheBaseIsAFileInTheWorkFolder() {
        ActionContext ctx = PackagesTestSupport.ctx(w, Map.of());
        KnowledgePackage k = new KnowledgePackage();
        k.buildIndex(ctx, null, List.of(Map.of("source", "a", "text", "Expenses are refunded within 30 days.")), null, null, null, null, null, null, 1000, 150, false, "Finance", null);
        assertTrue(Files.exists(w.resolve("knowledge-bases/Finance.json.gz")));
        assertFalse(((List<Object>) k.search(ctx, null, "refunded", 3, null, null, "Finance").get("results")).isEmpty());
    }
}
