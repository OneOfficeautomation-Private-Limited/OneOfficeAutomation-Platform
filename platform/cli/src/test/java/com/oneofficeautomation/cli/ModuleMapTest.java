package com.oneofficeautomation.cli;

import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The module map (module-map.json at the repository root, docs/architecture/13-modules.md): which module owns each file,
 * and the boundary report that shows where modules still reach into each other.
 *
 * <p>Phase 0 of the modularisation: the map must be valid and complete, and every module that has been moved out
 * (listed in {@link #ENFORCED}) must keep its boundaries. For the others the report only counts what is left to untangle,
 * in build/reports/module-boundaries.md, so each later phase can see its progress.
 */
class ModuleMapTest {
    static final Path REPO = ArchitectureTest.ROOT.resolve("..").toAbsolutePath().normalize();
    static final Path CR = REPO.resolve("platform/control-room/src/main/java/com/oneofficeautomation/controlroom");
    static final Path CONSOLE = REPO.resolve("platform/ui/src/console");
    /** Modules whose boundaries are already enforced: the kernel may not name their classes, nor may other feature modules. */
    static final Set<String> ENFORCED = Set.of("document-automation", "coe", "ai-services");

    record Module(String id, int layer, List<String> paths, List<Pattern> patterns, List<String> dependsOn) {}

    @SuppressWarnings("unchecked")
    static List<Module> modules() throws IOException {
        Map<String, Object> doc = (Map<String, Object>) JsonLite.parse(Files.readString(REPO.resolve("module-map.json"), StandardCharsets.UTF_8));
        List<Module> out = new ArrayList<>();
        for (Object o : (List<Object>) doc.get("modules")) {
            Map<String, Object> m = (Map<String, Object>) o;
            List<String> paths = ((List<Object>) m.get("paths")).stream().map(String::valueOf).toList();
            out.add(new Module((String) m.get("id"), ((Number) m.get("layer")).intValue(), paths, paths.stream().map(ModuleMapTest::glob).toList(),
                    ((List<Object>) m.getOrDefault("dependsOn", List.of())).stream().map(String::valueOf).toList()));
        }
        return out;
    }

    /** ** is any path, * any name within one folder. */
    static Pattern glob(String g) {
        StringBuilder r = new StringBuilder();
        for (int i = 0; i < g.length(); i++) {
            char c = g.charAt(i);
            if (c == '*' && i + 1 < g.length() && g.charAt(i + 1) == '*') { r.append(".*"); i++; }
            else if (c == '*') r.append("[^/]*");
            else r.append(Pattern.quote(String.valueOf(c)));
        }
        return Pattern.compile(r.toString());
    }

    /** How specific a path pattern is: an exact file beats a folder, and a deeper folder beats a shallower one. */
    static int specificity(String g) { int star = g.indexOf('*'); return star < 0 ? 100_000 + g.length() : star; }

    /** The module owning a repository-relative path (forward slashes), or null when no module claims it. */
    static String owner(List<Module> mods, String rel) {
        String best = null; int score = -1;
        for (Module m : mods)
            for (int i = 0; i < m.paths().size(); i++)
                if (m.patterns().get(i).matcher(rel).matches() && specificity(m.paths().get(i)) > score) { best = m.id(); score = specificity(m.paths().get(i)); }
        return best;
    }

    static String rel(Path p) { return REPO.relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/'); }

    static List<Path> files(Path dir, String suffix) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.walk(dir)) { return s.filter(p -> p.toString().endsWith(suffix)).sorted().toList(); }
    }

    @Test void theMapIsValid() throws IOException {
        List<Module> mods = modules();
        Set<String> ids = new HashSet<>();
        for (Module m : mods) assertTrue(ids.add(m.id()), "module ids are unique: " + m.id());
        for (Module m : mods) {
            for (String d : m.dependsOn()) {
                assertTrue(ids.contains(d), m.id() + " depends on a module that is not in the map: " + d);
                Module dm = mods.stream().filter(x -> x.id().equals(d)).findFirst().orElseThrow();
                assertTrue(dm.layer() <= m.layer(), m.id() + " (layer " + m.layer() + ") may not depend on " + d + " (layer " + dm.layer() + ")");
            }
            for (String p : m.paths())
                if (!p.contains("*")) assertTrue(Files.exists(REPO.resolve(p)), m.id() + " names a file that does not exist: " + p);
        }
    }

    @Test void everyCommandCenterClassAndConsolePageHasAnOwner() throws IOException {
        List<Module> mods = modules();
        List<String> orphans = new ArrayList<>();
        for (Path p : files(CR, ".java")) if (owner(mods, rel(p)) == null) orphans.add(rel(p));
        for (Path p : files(CONSOLE, ".tsx")) if (owner(mods, rel(p)) == null) orphans.add(rel(p));
        assertEquals(List.of(), orphans, "every file belongs to a module (module-map.json)");
    }

    /** One place where a module reaches into another: from, to, file and the name it uses. */
    record Crossing(String from, String to, String file, String name) {}

    static final Pattern IMPORT = Pattern.compile("from \"\\./([A-Za-z0-9_]+)\"");

    /** Command Center classes and console pages that name another module's class or page, other than the kernel. */
    static List<Crossing> crossings(List<Module> mods) throws IOException {
        Map<String, String> classOwner = new TreeMap<>();
        for (Path p : files(CR, ".java")) classOwner.put(p.getFileName().toString().replace(".java", ""), owner(mods, rel(p)));
        List<Crossing> out = new ArrayList<>();
        for (Path p : files(CR, ".java")) {
            String from = owner(mods, rel(p)), self = p.getFileName().toString().replace(".java", "");
            String code = Files.readString(p, StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
            for (Map.Entry<String, String> c : classOwner.entrySet()) {
                if (c.getKey().equals(self) || c.getValue().equals(from) || c.getValue().equals("kernel")) continue;
                if (Pattern.compile("\\b" + c.getKey() + "\\b").matcher(code).find()) out.add(new Crossing(from, c.getValue(), rel(p), c.getKey()));
            }
        }
        for (Path p : files(CONSOLE, ".tsx")) {
            if (p.getFileName().toString().contains(".test.")) continue;
            String from = owner(mods, rel(p));
            Matcher m = IMPORT.matcher(Files.readString(p, StandardCharsets.UTF_8));
            while (m.find()) {
                Path target = CONSOLE.resolve(m.group(1) + ".tsx");
                if (!Files.exists(target)) continue;
                String to = owner(mods, rel(target));
                if (!to.equals(from) && !to.equals("kernel") && !to.equals("apps")) out.add(new Crossing(from, to, rel(p), m.group(1)));
            }
        }
        return out;
    }

    @Test void boundaryReport() throws IOException {
        List<Module> mods = modules();
        List<Crossing> all = crossings(mods);
        Map<String, List<Crossing>> byPair = new TreeMap<>();
        for (Crossing c : all) byPair.computeIfAbsent(c.from() + " -> " + c.to(), k -> new ArrayList<>()).add(c);
        StringBuilder md = new StringBuilder("# Module boundary report\n\nGenerated by ModuleMapTest from module-map.json. Each line is a place where one module ")
                .append("names another module's class or page directly. The kernel naming a feature module, and feature modules naming each other, are what ")
                .append("the modularisation removes, phase by phase (docs/architecture/13-modules.md).\n\n")
                .append("Total crossings: ").append(all.size()).append("\n\n| From -> to | Count | Where |\n| --- | --- | --- |\n");
        for (Map.Entry<String, List<Crossing>> e : byPair.entrySet()) {
            Set<String> where = new TreeSet<>();
            for (Crossing c : e.getValue()) where.add(c.file().substring(c.file().lastIndexOf('/') + 1) + " (" + c.name() + ")");
            md.append("| ").append(e.getKey()).append(" | ").append(e.getValue().size()).append(" | ").append(String.join(", ", where)).append(" |\n");
        }
        Path report = ArchitectureTest.ROOT.resolve("cli/build/reports/module-boundaries.md");
        Files.createDirectories(report.getParent());
        Files.writeString(report, md, StandardCharsets.UTF_8);
        System.out.println("Module boundary report: " + all.size() + " crossings in " + byPair.size() + " module pairs -> " + report.toAbsolutePath().normalize());
        // modules that have been moved out keep their boundaries
        // (the console shell, module "apps", is the composition root: it may wire a module's pages in)
        List<Crossing> broken = all.stream().filter(c -> !c.from().equals("apps") && (ENFORCED.contains(c.to()) || ENFORCED.contains(c.from()))).toList();
        assertEquals(List.of(), broken, "modules moved out of the kernel are reached only through the module contract: " + ENFORCED);
    }
}
