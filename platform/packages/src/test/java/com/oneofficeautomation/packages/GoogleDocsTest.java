package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ConnectorsTest.Call;
import com.oneofficeautomation.packages.ConnectorsTest.Fake;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.*;

import static com.oneofficeautomation.packages.ConnectorsTest.ok;
import static com.oneofficeautomation.packages.ConnectorsTest.q;
import static com.oneofficeautomation.packages.GoogleGapsTest.*;
import static com.oneofficeautomation.packages.PackagesTest.tmp;
import static org.junit.jupiter.api.Assertions.*;

/** Google Docs actions against a local server that imitates the Docs API v1 (and Drive for moving a new document into a folder). */
class GoogleDocsTest {

    static final String DOC = "{\"documentId\":\"D1\",\"title\":\"Offer letter\",\"body\":{\"content\":["
            + "{\"sectionBreak\":{}},"
            + "{\"paragraph\":{\"elements\":[{\"textRun\":{\"content\":\"Dear \"}},{\"textRun\":{\"content\":\"{{name}},\\n\"}}]}},"
            + "{\"table\":{\"tableRows\":[{\"tableCells\":[{\"content\":[{\"paragraph\":{\"elements\":[{\"textRun\":{\"content\":\"Salary\\n\"}}]}}]},"
            + "{\"content\":[{\"paragraph\":{\"elements\":[{\"textRun\":{\"content\":\"{{amount}}\\n\"}}]}}]}]}]}},"
            + "{\"paragraph\":{\"elements\":[{\"textRun\":{\"content\":\"Regards\\n\"}}]}}]}}";

    static final String EMPTY_DOC = "{\"documentId\":\"E1\",\"title\":\"Blank\",\"body\":{\"content\":[{\"sectionBreak\":{}},"
            + "{\"paragraph\":{\"elements\":[{\"textRun\":{\"content\":\"\\n\"}}]}}]}}";

    static Fake docsFake() throws Exception {
        Fake f = new Fake();
        f.on("POST /v1/documents", c -> ok("{\"documentId\":\"NEWDOC\",\"title\":\"Minutes\"}"))
         .on("GET /v1/documents/D1", c -> ok(DOC))
         .on("GET /v1/documents/E1", c -> ok(EMPTY_DOC))
         .on("POST /v1/documents/[^/]+:batchUpdate", c -> {
             List<Object> replies = new ArrayList<>();
             int i = 0;
             for (Object r : (List<?>) json(c).get("requests"))
                 replies.add(m(r).containsKey("replaceAllText") ? Map.of("replaceAllText", Map.of("occurrencesChanged", ++i * 2)) : Map.of());
             return ok(com.oneofficeautomation.sdk.JsonLite.write(Map.of("documentId", "x", "replies", replies)));
         })
         .on("GET /drive/v3/files/NEWDOC", c -> ok("{\"id\":\"NEWDOC\",\"parents\":[\"root\"]}"))
         .on("PATCH /drive/v3/files/NEWDOC", c -> ok("{\"id\":\"NEWDOC\"}"))
         .on("GET /v1/documents/(NOAUTH|DENIED|GONE|BUSY)", c -> switch (c.path().substring(c.path().lastIndexOf('/') + 1)) {
             case "NOAUTH" -> new Object[]{401, "{\"error\":{\"code\":401,\"message\":\"Request had invalid authentication credentials.\",\"status\":\"UNAUTHENTICATED\"}}"};
             case "DENIED" -> new Object[]{403, "{\"error\":{\"code\":403,\"message\":\"The caller does not have permission\",\"status\":\"PERMISSION_DENIED\"}}"};
             case "GONE" -> new Object[]{404, "{\"error\":{\"code\":404,\"message\":\"Requested entity was not found.\",\"status\":\"NOT_FOUND\"}}"};
             default -> new Object[]{429, "{\"error\":{\"code\":429,\"message\":\"Quota exceeded\",\"status\":\"RESOURCE_EXHAUSTED\"}}"};
         });
        return f;
    }

    static List<Call> batches(Fake f, String id) {
        synchronized (f.calls) { return f.calls.stream().filter(c -> c.method().equals("POST") && c.path().equals("/v1/documents/" + id + ":batchUpdate")).toList(); }
    }

    @Test void createDocumentWithTextInAFolder() throws Exception {
        try (Fake f = docsFake()) {
            Path w = tmp();
            Map<String, Object> o = act(f, w, "docs_create_document", Map.of("title", "Minutes", "text", "Agenda\nItem 1", "folder_id", "FOLDER9"), "document_id", "url");
            assertEquals("NEWDOC", o.get("document_id"));
            assertEquals("https://docs.google.com/document/d/NEWDOC/edit", o.get("url"));
            assertEquals("Minutes", json(f.last("POST", "/v1/documents")).get("title"));
            assertEquals("Bearer tok-123", f.last("POST", "/v1/documents").auth());
            Map<String, Object> insert = m(req0(f.last("POST", "/v1/documents/NEWDOC:batchUpdate")).get("insertText"));
            assertEquals("Agenda\nItem 1", insert.get("text"));
            assertTrue(insert.containsKey("endOfSegmentLocation"), "text goes at the end of the body");
            assertTrue(q(f.last("PATCH", "/drive/v3/files/NEWDOC")).contains("addParents=FOLDER9"), "moved into the folder through Drive");

            f.calls.clear();
            o = act(f, w, "docs_create_document", Map.of("title", "Empty"), "document_id");
            assertEquals("NEWDOC", o.get("document_id"));
            assertTrue(batches(f, "NEWDOC").isEmpty(), "no text, no batchUpdate");
            assertNull(f.last("PATCH", "/drive/v3/files/NEWDOC"), "no folder, no move");
        }
    }

    @Test void readTextIncludesTablesAndAcceptsLinks() throws Exception {
        try (Fake f = docsFake()) {
            Path w = tmp();
            Map<String, Object> o = act(f, w, "docs_read_text", Map.of("document_id", "https://docs.google.com/document/d/D1/edit?tab=t.0"), "text", "title");
            assertEquals("Dear {{name}},\nSalary\n{{amount}}\nRegards\n", o.get("text"));
            assertEquals("Offer letter", o.get("title"));
            assertEquals("/v1/documents/D1", f.last("GET", "/v1/documents/D1").path());
        }
    }

    @Test void replaceTextSendsOneReplaceAllPerPairAndAddsUpCounts() throws Exception {
        try (Fake f = docsFake()) {
            Path w = tmp();
            Map<String, Object> pairs = new LinkedHashMap<>();
            pairs.put("{{name}}", "Ann");
            pairs.put("{{amount}}", 5000);
            Map<String, Object> o = act(f, w, "docs_replace_text", Map.of("document_id", "D1", "replacements", pairs, "match_case", true), "occurrences_changed");
            assertEquals(6, n(o.get("occurrences_changed")), "2 + 4 from the two replies");
            List<?> reqs = (List<?>) json(f.last("POST", "/v1/documents/D1:batchUpdate")).get("requests");
            assertEquals(2, reqs.size());
            Map<String, Object> first = m(m(reqs.get(0)).get("replaceAllText"));
            assertEquals(Map.of("text", "{{name}}", "matchCase", true), first.get("containsText"));
            assertEquals("Ann", first.get("replaceText"));
            assertEquals("5000", m(m(reqs.get(1)).get("replaceAllText")).get("replaceText"));

            act(f, w, "docs_replace_text", Map.of("document_id", "D1", "replacements", Map.of("x", "y")));
            assertEquals(false, m(m(req0(f.last("POST", "/v1/documents/D1:batchUpdate")).get("replaceAllText")).get("containsText")).get("matchCase"), "match_case defaults to false");
        }
    }

    @Test void appendTextStartsANewParagraphOnlyAfterText() throws Exception {
        try (Fake f = docsFake()) {
            Path w = tmp();
            act(f, w, "docs_append_text", Map.of("document_id", "D1", "text", "P.S. Welcome"));
            assertEquals("\nP.S. Welcome", m(req0(f.last("POST", "/v1/documents/D1:batchUpdate")).get("insertText")).get("text"));

            act(f, w, "docs_append_text", Map.of("document_id", "E1", "text", "First line"));
            assertEquals("First line", m(req0(f.last("POST", "/v1/documents/E1:batchUpdate")).get("insertText")).get("text"), "empty document: no leading line break");

            f.calls.clear();
            act(f, w, "docs_append_text", Map.of("document_id", "D1", "text", " (signed)", "new_paragraph", false));
            assertEquals(" (signed)", m(req0(f.last("POST", "/v1/documents/D1:batchUpdate")).get("insertText")).get("text"));
            assertNull(f.last("GET", "/v1/documents/D1"), "the document is not read when no new paragraph is wanted");
        }
    }

    @Test void inputErrorsFailBeforeAnyRequest() throws Exception {
        try (Fake f = docsFake()) {
            assertEquals("GOOGLE.BAD_INPUT", err(f, "docs_read_text", Map.of("document_id", "not an id!")).get("code"));
            assertEquals("GOOGLE.BAD_INPUT", err(f, "docs_read_text", Map.of("document_id", "https://docs.google.com/spreadsheets/d/S1/edit")).get("code"));
            assertEquals("GOOGLE.BAD_INPUT", err(f, "docs_replace_text", Map.of("document_id", "D1", "replacements", Map.of())).get("code"));
            assertEquals("GOOGLE.BAD_INPUT", err(f, "docs_replace_text", Map.of("document_id", "D1", "replacements", Map.of("", "x"))).get("code"));
            assertEquals("GOOGLE.BAD_INPUT", err(f, "docs_append_text", Map.of("document_id", "D1", "text", "")).get("code"));
            assertTrue(f.calls.isEmpty(), "no request was sent: " + f.calls.stream().map(Call::path).toList());
        }
    }

    @Test void serviceErrorsAreMappedToTypedCodes() throws Exception {
        try (Fake f = docsFake()) {
            Map<String, Object> e = err(f, "docs_read_text", Map.of("document_id", "NOAUTH"));
            assertEquals("GOOGLE.AUTH_FAILED", e.get("code"));
            assertTrue(String.valueOf(e.get("message")).contains("invalid authentication credentials"));
            assertEquals("GOOGLE.FORBIDDEN", err(f, "docs_read_text", Map.of("document_id", "DENIED")).get("code"));
            assertEquals("GOOGLE.NOT_FOUND", err(f, "docs_append_text", Map.of("document_id", "GONE", "text", "x")).get("code"));
            assertEquals("GOOGLE.RATE_LIMITED", err(f, "docs_read_text", Map.of("document_id", "BUSY")).get("code"));
        }
    }
}
