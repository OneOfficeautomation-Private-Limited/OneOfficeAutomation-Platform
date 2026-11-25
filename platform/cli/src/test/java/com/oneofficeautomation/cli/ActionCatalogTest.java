package com.oneofficeautomation.cli;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.ActionCatalog;
import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The action catalog OneChat reads (S6, 25 Nov 2026; contract section 17): the copy in the repository is this
 * release's, its checksum is right, and every action of the toolkit reference is in it.
 */
class ActionCatalogTest {
    static final Path ROOT = Files.isDirectory(Path.of("sdk")) ? Path.of(".") : Path.of("..");
    static final Path FILE = ROOT.resolve("../docs/reference/action-catalog.jsonl");

    @Test
    void theRepositoryCopyIsThisReleases() throws IOException {
        assertTrue(Files.exists(FILE), "Make it: ooa catalog --out docs/reference/action-catalog.jsonl");
        String want = ActionCatalog.jsonl(FirstPartyPackages.registry().allManifests());
        String have = Files.readString(FILE, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertEquals(want.lines().findFirst().orElse(""), have.lines().findFirst().orElse(""),
                "docs/reference/action-catalog.jsonl is out of date; run: ooa catalog --out docs/reference/action-catalog.jsonl");
        assertEquals(want, have, "docs/reference/action-catalog.jsonl is out of date; run: ooa catalog --out docs/reference/action-catalog.jsonl");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theChecksumAndShapeAreAsTheContractSays() {
        String text = ActionCatalog.jsonl(FirstPartyPackages.registry().allManifests());
        List<String> lines = text.lines().toList();
        Map<String, Object> header = (Map<String, Object>) JsonLite.parse(lines.get(0));
        assertEquals("ooa-action-catalog/v1", header.get("format"));
        List<String> entries = lines.subList(1, lines.size());
        assertEquals(entries.size(), ((Number) header.get("entries")).intValue());
        assertEquals(ActionCatalog.sha256(String.join("\n", entries).getBytes(StandardCharsets.UTF_8)), header.get("sha256"));
        Set<String> kinds = new HashSet<>();
        String previous = "";
        for (String l : entries) {
            Map<String, Object> e = (Map<String, Object>) JsonLite.parse(l);
            assertEquals(JsonLite.canonical(e), l, "each entry is canonical JSON");
            kinds.add(String.valueOf(e.get("kind")));
            for (String k : List.of("id", "kind", "toolkit_id", "toolkit", "name", "description", "inputs", "outputs")) assertTrue(e.containsKey(k), k + " in " + l);
            String key = e.get("kind") + " " + e.get("id");
            assertTrue(previous.isEmpty() || !e.get("kind").equals(previous.split(" ")[0]) || key.compareTo(previous) > 0, "sorted by kind then id: " + key);
            previous = key;
        }
        assertEquals(Set.of("action", "condition", "loop_type"), kinds);
        assertTrue(text.contains("\"id\":\"ooa.csv/read\""), "a known action is there");
    }

    @Test
    void everyActionOfTheToolkitReferenceIsInIt() throws IOException {
        String text = ActionCatalog.jsonl(FirstPartyPackages.registry().allManifests());
        List<String> missing = new ArrayList<>();
        try (var pages = Files.list(ROOT.resolve("../docs/reference/toolkits"))) {
            for (Path p : pages.filter(f -> f.toString().endsWith(".md")).toList())
                for (String line : Files.readAllLines(p))
                    if (line.matches("^`[a-z0-9_.]+/[A-Za-z0-9_.]+` \\(action\\)$")) {
                        String id = line.substring(1, line.indexOf('`', 1));
                        if (!text.contains("\"id\":\"" + id + "\"")) missing.add(id);
                    }
        }
        assertTrue(missing.isEmpty(), "actions in the toolkit reference but not in the catalog: " + missing);
    }
}
