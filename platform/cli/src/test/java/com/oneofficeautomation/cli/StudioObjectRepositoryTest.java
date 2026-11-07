package com.oneofficeautomation.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

import static com.oneofficeautomation.cli.StudioServerRoutesTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * G42 in Studio: an object repository is an item of its own; a captured object is saved to it as application, screen
 * and element; the picker lists every repository's elements; an agent that uses one names it as a dependency and gets
 * a problem when the element is missing; the step runs with the element's object.
 */
class StudioObjectRepositoryTest {
    static final Map<String, Object> OBJECT = Map.of("technology", "web", "search_by", "css", "css", "#name",
            "properties", List.of(Map.of("name", "id", "value", "name", "match", "equals", "use", true)));

    @SuppressWarnings("unchecked")
    @Test void createSaveUseAndCheck() throws Exception {
        Path root = Files.createTempDirectory("ooa-objrepo");
        int port = StudioControlRoomTest.freePort();
        new StudioServer(root, port, Path.of("../../examples/refund")).start();
        String base = "http://127.0.0.1:" + port;

        // a new repository: an item of its own, kept when renamed
        String repo = String.valueOf(call(base, "POST", "/api/files/item", Map.of("parent", "My Agents", "name", "Portal", "type", "objects")).get("path"));
        assertEquals("My Agents/Portal.objects.json", repo);
        assertEquals("objects", call(base, "GET", "/api/item?path=" + enc(repo), null).get("type"));
        assertTrue(Files.readString(root.resolve(repo)).replace(" ", "").contains("\"kind\":\"ObjectRepository\""));

        // a captured object goes in as Application / Screen / Element; the same name again needs replace
        Map<String, Object> ref = call(base, "POST", "/api/objects/add", Map.of("repository", repo, "application", "Portal", "screen", "Sign in", "name", "Name box", "object", OBJECT));
        assertEquals(repo, ref.get("repository"));
        assertEquals("Portal/Sign in/Name box", ref.get("element"));
        assertEquals("STUDIO.EXISTS", code(call(base, "POST", "/api/objects/add", Map.of("repository", repo, "application", "Portal", "screen", "Sign in", "name", "Name box", "object", OBJECT))));
        assertEquals(200, call(base, "POST", "/api/objects/add", Map.of("repository", repo, "application", "Portal", "screen", "Sign in", "name", "Name box", "object", OBJECT, "replace", true)).get("_status"));
        assertEquals("STUDIO.BAD_NAME", code(call(base, "POST", "/api/objects/add", Map.of("repository", repo, "application", "A/B", "screen", "s", "name", "n", "object", OBJECT))));
        assertEquals("STUDIO.BAD_INPUT", code(call(base, "POST", "/api/objects/add", Map.of("repository", repo, "application", "A", "screen", "s", "name", "n", "object", Map.of("repository", repo, "element", "a/b/c")))));
        call(base, "POST", "/api/objects/add", Map.of("repository", repo, "application", "Portal", "screen", "Sign in", "name", "Submit", "description", "The blue button", "object", OBJECT));

        // the picker's catalogue
        List<Map<String, Object>> cat = (List<Map<String, Object>>) call(base, "GET", "/api/objects/catalogue", null).get("repositories");
        Map<String, Object> mine = cat.stream().filter(r -> repo.equals(r.get("path"))).findFirst().orElseThrow();
        assertEquals(List.of("Portal/Sign in/Name box", "Portal/Sign in/Submit"), ((List<Map<String, Object>>) mine.get("elements")).stream().map(e -> e.get("element")).toList());
        assertEquals("The blue button", ((List<Map<String, Object>>) mine.get("elements")).get(1).get("description"));

        // an agent using an element: a dependency; a missing element is a problem when saved
        String bot = String.valueOf(call(base, "POST", "/api/files/bot", Map.of("parent", "My Agents", "name", "Sign in")).get("path"));
        Map<String, Object> flow = new LinkedHashMap<>((Map<String, Object>) call(base, "GET", "/api/bot?path=" + enc(bot), null).get("flow"));
        flow.put("steps", List.of(Map.of("id", "s1", "action", "ooa.browser/click", "args", Map.of("object", ref)),
                Map.of("id", "s2", "action", "ooa.browser/click", "args", Map.of("object", Map.of("repository", repo, "element", "Portal/Sign in/Gone")))));
        List<Map<String, Object>> problems = (List<Map<String, Object>>) call(base, "PUT", "/api/bot?path=" + enc(bot), flow).get("problems");
        assertTrue(problems.stream().anyMatch(p -> "s2".equals(p.get("stepId")) && String.valueOf(p.get("message")).contains("Gone")), String.valueOf(problems));
        assertTrue(problems.stream().noneMatch(p -> "s1".equals(p.get("stepId")) && String.valueOf(p.get("message")).contains("repository")), String.valueOf(problems));
        Map<String, Object> deps = call(base, "GET", "/api/deps?path=" + enc(bot), null);
        assertTrue(((List<Map<String, Object>>) deps.get("files")).stream().anyMatch(f -> repo.equals(f.get("path")) && Boolean.FALSE.equals(f.get("missing"))), String.valueOf(deps));
    }
}
