package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.oneofficeautomation.packages.ConnectorsTest.*;
import static com.oneofficeautomation.packages.PackagesTest.tmp;
import static org.junit.jupiter.api.Assertions.*;

/** A5: knowledge indexes over documents, search by words and meaning, and answers that cite their passages. */
class KnowledgePackageTest {

    static byte[] docx(String... paragraphs) throws Exception {
        StringBuilder x = new StringBuilder("<w:document><w:body>");
        for (String p : paragraphs) x.append("<w:p><w:r><w:t>").append(p).append("</w:t></w:r></w:p>");
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(b)) {
            z.putNextEntry(new ZipEntry("word/document.xml"));
            z.write(x.append("</w:body></w:document>").toString().getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        return b.toByteArray();
    }

    /** A stand-in embedding model: one dimension per topic, so "reimbursed" lands next to the refund passage. */
    static List<Object> vectorOf(String t) {
        String s = t.toLowerCase(Locale.ROOT);
        return List.of(s.contains("refund") || s.contains("reimburse") ? 1 : 0, s.contains("holiday") || s.contains("vacation") ? 1 : 0, s.contains("laptop") ? 1 : 0, 0.01);
    }

    @Test @SuppressWarnings("unchecked")
    void indexesSearchesAndAnswersFromDocuments() throws Exception {
        Path w = tmp();
        Path docs = Files.createDirectories(w.resolve("policies"));
        Files.writeString(docs.resolve("refunds.md"), "# Refunds\n\nCustomers get a refund within 14 days of a return. Refunds go back to the card that paid.");
        Files.writeString(docs.resolve("holidays.html"), "<html><body><h1>Holidays</h1><p>Staff have 25 days of holiday a year.</p><script>var x=1;</script></body></html>");
        Files.write(docs.resolve("it.docx"), docx("Laptops are replaced every three years.", "Ask the service desk for a new laptop."));
        Files.write(docs.resolve("logo.png"), new byte[]{1, 2, 3});
        ActionContext ctx = AiGapsTest.ctx(w, new ArrayList<>());
        KnowledgePackage k = new KnowledgePackage();

        // words only: no model needed
        Map<String, Object> built = k.buildIndex(ctx, List.of("policies"), List.of(Map.of("source", "travel.pdf", "text", "Book travel through the portal.")),
            "kb/words.json.gz", null, null, null, null, null, 1000, 150, false);
        assertEquals(BigDecimal.valueOf(4), built.get("documents"));
        assertEquals(1, ((List<Object>) built.get("skipped")).size(), "the picture is skipped with its reason");
        List<Object> hits = (List<Object>) k.search(ctx, "kb/words.json.gz", "how many holiday days", 3, null, null).get("results");
        assertEquals("holidays.html", ((Map<String, Object>) hits.get(0)).get("source"));
        assertFalse(String.valueOf(((Map<String, Object>) hits.get(0)).get("text")).contains("var x"), "scripts are not indexed");
        assertTrue(((List<Object>) k.search(ctx, "kb/words.json.gz", "reimbursement", 3, null, null).get("results")).isEmpty(), "words alone do not find the refund passage");

        try (Fake f = new Fake()) {
            f.on("POST /v1/embeddings", c -> {
                List<Object> data = new ArrayList<>();
                int i = 0;
                for (Object in : (List<Object>) c.json().get("input")) data.add(Map.of("index", i++, "embedding", vectorOf(String.valueOf(in))));
                return ok(JsonLite.write(Map.of("data", data, "usage", Map.of("prompt_tokens", 7))));
            });
            f.on("POST /v1/chat/completions", c -> ok(String.format(AiGapsTest.CHAT_OK, "Refunds are paid within 14 days [1].")));
            k.buildIndex(ctx, List.of("policies"), null, "kb/meaning.json", null, "openai_compatible", "emb-1", null, f.url() + "/v1", 1000, 150, false);
            Map<String, Object> idx = (Map<String, Object>) JsonLite.parse(Files.readString(w.resolve("kb/meaning.json")));
            assertEquals("emb-1", ((Map<String, Object>) idx.get("embedding_model")).get("model"));

            // by meaning: "reimbursement" finds the refund passage
            List<Object> sem = (List<Object>) k.search(ctx, "kb/meaning.json", "reimbursement", 2, null, null).get("results");
            assertEquals("refunds.md", ((Map<String, Object>) sem.get(0)).get("source"));

            Map<String, Object> a = k.ask(ctx, "kb/meaning.json", "Will I be reimbursed?", 2, null, "openai_compatible", "chat-1", null, f.url() + "/v1", null, 512, true, 0);
            assertEquals("Refunds are paid within 14 days [1].", a.get("answer"));
            assertEquals(Boolean.TRUE, a.get("found"));
            String sent = f.last("POST", "/v1/chat/completions").text();
            assertTrue(sent.contains("[1] (refunds.md)") && sent.contains("Question: Will I be reimbursed?"), sent);
            assertTrue(sent.contains("only the numbered passages"), "the model is told to answer from the passages only");
            assertTrue(Files.readString(w.resolve("ai-governance.jsonl")).contains("\"purpose\":\"embed\""), "embedding calls are in the AI governance log");
        }

        // nothing matches: the model is not asked
        Map<String, Object> none = k.ask(ctx, "kb/words.json.gz", "zebra xylophone", 3, null, "openai", "gpt", null, "http://127.0.0.1:9", null, 512, true, 0);
        assertEquals(Boolean.FALSE, none.get("found"));
        assertEquals("KNOWLEDGE.NO_INDEX", assertThrows(ActionException.class, () -> k.search(ctx, "kb/none.json", "x", 3, null, null)).code());
    }

    @Test
    void splitsAtSentencesWithOverlap() {
        String text = "One sentence here. ".repeat(200);
        List<String> parts = KnowledgePackage.split(text, 300, 50);
        assertTrue(parts.size() > 10);
        for (String p : parts) { assertTrue(p.length() <= 300, p.length() + ""); assertTrue(p.endsWith("."), p); }
        assertEquals(List.of("short"), KnowledgePackage.split("  short  ", 300, 50));
        float[] v = {0.5f, -1.25f, 3f};
        assertArrayEquals(v, KnowledgePackage.decode(KnowledgePackage.encode(v)));
    }
}
