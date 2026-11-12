package com.oneofficeautomation.runner;

import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared object repositories (gap G42): captured web and desktop objects kept once in a workspace file and used by many
 * agents, so a changed screen is fixed in one place.
 * <p>
 * A repository is a file ending in {@code .objects.json}:
 * <pre>{"kind": "ObjectRepository", "name": "SAP", "description": "...",
 *  "applications": [{"name": "SAP GUI", "description": "...", "screens": [{"name": "Login", "elements": [
 *      {"name": "User name", "description": "...", "object": { a captured object, as in a step }}]}]}]}</pre>
 * A step uses an element by reference instead of a captured object of its own:
 * {@code "object": {"repository": "Shared/SAP.objects.json", "element": "SAP GUI/Login/User name"}}. The runner puts the
 * element's object in its place just before the step runs, so every agent picks up a change to the repository on its
 * next run. One CommandCenter sends the repository files an agent refers to with each run, like its other files.
 */
public final class ObjectRepositories {
    private ObjectRepositories() { }

    public static final String SUFFIX = ".objects.json";
    public static final String KIND = "ObjectRepository";

    private record Cached(long modified, long size, Map<String, Object> repo) { }
    private static final Map<Path, Cached> CACHE = new ConcurrentHashMap<>();

    /** True for {"repository": "...", "element": "..."} (and nothing else). */
    public static boolean isReference(Object v) {
        return v instanceof Map<?, ?> m && m.size() == 2 && m.get("repository") instanceof String r && !r.isBlank() && m.get("element") instanceof String e && !e.isBlank();
    }

    /** The element's captured object, read from the repository file in the workspace. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> resolve(Path workDir, Object reference) {
        Map<String, Object> ref = (Map<String, Object>) reference;
        String repo = String.valueOf(ref.get("repository")), element = String.valueOf(ref.get("element"));
        Map<String, Object> el = find(read(workDir, repo), element);
        if (el == null) throw new OoaException("OBJECT.NOT_IN_REPOSITORY", "The object repository " + repo + " has no element " + element,
                "Open the repository and check the application, screen and element names, or choose the element again.");
        if (!(el.get("object") instanceof Map<?, ?> o)) throw new OoaException("OBJECT.NOT_IN_REPOSITORY", "The element " + element + " in " + repo + " has no captured object",
                "Capture the element again in the repository.");
        Map<String, Object> copy = (Map<String, Object>) JsonLite.parse(JsonLite.write(o));
        copy.remove("screenshot"); copy.remove("window_shot");   // pictures are for people, not for finding the element
        return copy;
    }

    /** A repository file, checked to be inside the workspace and read again only when it changed. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> read(Path workDir, String repo) {
        if (!repo.endsWith(SUFFIX)) throw new OoaException("OBJECT.BAD_REPOSITORY", "An object repository is a file ending in " + SUFFIX + ": " + repo, "Choose an object repository file (.objects.json) inside the workspace; Capture makes one.");
        Path root = workDir.toAbsolutePath().normalize(), p = root.resolve(repo.replace('\\', '/')).normalize();
        if (!p.startsWith(root)) throw new OoaException("OBJECT.BAD_REPOSITORY", "The object repository must be inside the workspace: " + repo, "Choose an object repository file (.objects.json) inside the workspace; Capture makes one.");
        if (!Files.isRegularFile(p)) throw new OoaException("OBJECT.REPOSITORY_NOT_FOUND", "Object repository not found: " + repo,
                "Check the path. From One CommandCenter, check the repository file in with the agent.");
        try {
            long mod = Files.getLastModifiedTime(p).toMillis(), size = Files.size(p);
            Cached c = CACHE.get(p);
            if (c != null && c.modified == mod && c.size == size) return c.repo;
            Object parsed = JsonLite.parse(Files.readString(p, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> m) || !KIND.equals(m.get("kind"))) throw new OoaException("OBJECT.BAD_REPOSITORY", repo + " is not an object repository", "Choose an object repository file (.objects.json) inside the workspace; Capture makes one.");
            if (CACHE.size() > 200) CACHE.clear();
            CACHE.put(p, new Cached(mod, size, (Map<String, Object>) m));
            return (Map<String, Object>) m;
        } catch (IOException | IllegalArgumentException e) {
            throw new OoaException("OBJECT.BAD_REPOSITORY", "Cannot read the object repository " + repo + ": " + e.getMessage(), "Choose an object repository file (.objects.json) inside the workspace; Capture makes one.");
        }
    }

    /** An element by its path "Application/Screen/Element" (names compared exactly). */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> find(Map<String, Object> repo, String path) {
        String[] parts = path.split("/", -1);
        if (parts.length != 3) return null;
        for (Object a : list(repo.get("applications")))
            if (a instanceof Map<?, ?> am && parts[0].equals(am.get("name")))
                for (Object s : list(am.get("screens")))
                    if (s instanceof Map<?, ?> sm && parts[1].equals(sm.get("name")))
                        for (Object e : list(sm.get("elements")))
                            if (e instanceof Map<?, ?> em && parts[2].equals(em.get("name"))) return (Map<String, Object>) em;
        return null;
    }

    /** Every element path of a repository, in order. */
    public static List<String> elements(Map<String, Object> repo) {
        List<String> out = new ArrayList<>();
        for (Object a : list(repo.get("applications")))
            if (a instanceof Map<?, ?> am) for (Object s : list(am.get("screens")))
                if (s instanceof Map<?, ?> sm) for (Object e : list(sm.get("elements")))
                    if (e instanceof Map<?, ?> em) out.add(am.get("name") + "/" + sm.get("name") + "/" + em.get("name"));
        return out;
    }

    /** The repository files an agent refers to: in any step's settings, its subflows and its error handler. */
    public static List<String> referenced(Map<String, Object> flow) {
        Set<String> out = new LinkedHashSet<>();
        for (Map<String, Object> s : Steps.walkFlow(flow)) collect(s.get("args"), out, 0);
        return new ArrayList<>(out);
    }

    /** The references in an agent: step id to "repository#element", for checks before it runs. */
    public static List<Map<String, Object>> references(Map<String, Object> flow) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> s : Steps.walkFlow(flow)) {
            Set<String> found = new LinkedHashSet<>();
            refs(s.get("args"), found, 0);
            for (String f : found) {
                int at = f.indexOf('#');
                out.add(Map.of("step", String.valueOf(s.get("id")), "repository", f.substring(0, at), "element", f.substring(at + 1)));
            }
        }
        return out;
    }

    private static void collect(Object v, Set<String> out, int depth) {
        Set<String> found = new LinkedHashSet<>();
        refs(v, found, depth);
        for (String f : found) out.add(f.substring(0, f.indexOf('#')));
    }

    private static void refs(Object v, Set<String> out, int depth) {
        if (depth > 30) return;
        if (isReference(v)) { Map<?, ?> m = (Map<?, ?>) v; out.add(m.get("repository") + "#" + m.get("element")); return; }
        if (v instanceof Map<?, ?> m) for (Object x : m.values()) refs(x, out, depth + 1);
        else if (v instanceof List<?> l) for (Object x : l) refs(x, out, depth + 1);
    }

    private static List<?> list(Object o) { return o instanceof List<?> l ? l : List.of(); }
}
