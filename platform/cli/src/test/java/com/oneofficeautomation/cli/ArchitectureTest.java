package com.oneofficeautomation.cli;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.PackageRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Enterprise and module-level rules, checked on every build:
 * module boundaries, no shell execution, hardened XML only, and complete, consistent command documentation.
 */
class ArchitectureTest {
    static final Path ROOT = Files.isDirectory(Path.of("sdk")) ? Path.of(".") : Path.of("..");
    static final List<String> OPTIONAL = List.of("packages-office", "packages-email", "packages-pdf", "packages-browser", "packages-transfer", "packages-windows");

    static List<Path> sources(String module) throws IOException {
        Path dir = ROOT.resolve(module).resolve("src/main/java");
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.walk(dir)) { return s.filter(p -> p.toString().endsWith(".java")).toList(); }
    }

    static List<String> violations(String module, Pattern forbidden) throws IOException {
        List<String> out = new ArrayList<>();
        for (Path p : sources(module)) {
            List<String> lines = Files.readAllLines(p);
            for (int i = 0; i < lines.size(); i++) if (forbidden.matcher(lines.get(i)).find()) out.add(ROOT.relativize(p) + ":" + (i + 1) + "  " + lines.get(i).trim());
        }
        return out;
    }

    @Test void moduleBoundaries() throws IOException {
        assertEquals(List.of(), violations("sdk", Pattern.compile("^import com\\.oneofficeautomation\\.(runner|packages|cli)")), "the SDK depends on nothing else");
        assertEquals(List.of(), violations("runner", Pattern.compile("^import com\\.oneofficeautomation\\.(packages|cli)")), "the Runner knows no packages and no Studio");
        assertEquals(List.of(), violations("packages", Pattern.compile("^import com\\.oneofficeautomation\\.(cli|packages\\.(office|email|pdf|browser|transfer|windows))")),
                "built-in packages do not depend on Studio or on optional modules");
        for (String m : OPTIONAL)
            assertEquals(List.of(), violations(m, Pattern.compile("^import com\\.oneofficeautomation\\.(cli\\.|packages\\.(office|email|pdf|browser|transfer|windows)\\.)(?!" + pkgOf(m) + ")")),
                    m + " depends only on the SDK, the Runner and built-in packages");
        assertEquals(List.of(), violations("cli", Pattern.compile("^import com\\.oneofficeautomation\\.packages\\.(office|email|pdf|browser|transfer|windows)")),
                "Studio loads optional modules only by name, so a missing module never breaks it");
        assertEquals(List.of(), violations("control-room", Pattern.compile("^import com\\.oneofficeautomation\\.(cli|agent)\\.")),
                "Control Room depends on neither Studio nor the Bot Agent");
        assertEquals(List.of(), violations("agent", Pattern.compile("^import com\\.oneofficeautomation\\.(cli|controlroom)\\.")),
                "the Bot Agent talks to Control Room only over HTTP, never through its code");
        assertEquals(List.of(), violations("runner", Pattern.compile("^import com\\.oneofficeautomation\\.(controlroom|agent)\\.")),
                "the Runner is the same everywhere: Studio, agents and Control Room validation");
    }

    static String pkgOf(String module) {
        return switch (module) { case "packages-office" -> "office"; case "packages-email" -> "email"; case "packages-pdf" -> "pdf";
            case "packages-browser" -> "browser"; case "packages-transfer" -> "transfer"; default -> "windows"; };
    }

    @Test void noShellExecutionAnywhere() throws IOException {
        Pattern shell = Pattern.compile("Runtime\\.getRuntime\\(\\)\\.exec|\"cmd(\\.exe)?\",\\s*\"/c\"|\"/bin/sh\"|\"sh\",\\s*\"-c\"|\"bash\",\\s*\"-c\"");
        List<String> all = new ArrayList<>();
        for (String m : List.of("sdk", "runner", "packages", "cli", "adapters-jackson", "control-room", "agent")) all.addAll(violations(m, shell));
        for (String m : OPTIONAL) all.addAll(violations(m, shell));
        assertEquals(List.of(), all, "programs are started with argument lists, never through a shell");
    }

    @Test void xmlParsingOnlyThroughTheHardenedParser() throws IOException {
        List<String> v = new ArrayList<>(violations("packages", Pattern.compile("DocumentBuilderFactory\\.newInstance|SAXParserFactory\\.newInstance|XMLInputFactory\\.newInstance")));
        v.removeIf(x -> x.contains("XmlSupport.java"));
        assertEquals(List.of(), v, "all XML goes through XmlSupport, which blocks XXE");
    }

    @Test void controlRoomUsesOnlyParameterisedSql() throws IOException {
        // every statement is a constant string with ? placeholders; values are never concatenated into SQL
        Pattern concat = Pattern.compile("db\\.(query|one|update)\\(\"[^\"]*\"\\s*\\+\\s*(?!\"|where|Math\\.|$)");
        assertEquals(List.of(), violations("control-room", concat), "SQL is built only from constants and placeholders");
    }

    @Test void packagesNeverPrintToTheConsole() throws IOException {
        List<String> v = new ArrayList<>(violations("packages", Pattern.compile("System\\.(out|err)\\.print")));
        for (String m : OPTIONAL) v.addAll(violations(m, Pattern.compile("System\\.(out|err)\\.print")));
        assertEquals(List.of(), v, "packages log through ActionContext so secrets are masked and logs reach the run log");
    }

    @Test @SuppressWarnings("unchecked") void everyCommandIsDocumentedAndConsistentlyNamed() {
        PackageRegistry reg = FirstPartyPackages.registry();
        List<String> problems = new ArrayList<>();
        Pattern id = Pattern.compile("^[a-z][a-z0-9_]*$");
        Set<String> pkgIds = new HashSet<>();
        for (Map<String, Object> m : reg.allManifests()) {
            String pid = String.valueOf(m.get("id"));
            if (!pkgIds.add(pid)) problems.add("duplicate package id " + pid);
            if (!pid.equals("core") && !pid.startsWith("ooa.")) problems.add(pid + ": first-party package ids start with ooa.");
            if (String.valueOf(m.getOrDefault("description", "")).isBlank()) problems.add(pid + ": no description");
            for (String kind : List.of("actions", "conditions", "iterators"))
                for (Object o : (List<Object>) m.getOrDefault(kind, List.of())) {
                    Map<String, Object> a = (Map<String, Object>) o;
                    String aid = pid + "/" + a.get("id");
                    if (!id.matcher(String.valueOf(a.get("id"))).matches()) problems.add(aid + ": ids are snake_case");
                    if (String.valueOf(a.getOrDefault("name", "")).isBlank()) problems.add(aid + ": no display name");
                    if (String.valueOf(a.getOrDefault("description", "")).length() < 10) problems.add(aid + ": description too short");
                    Map<String, Object> props = (Map<String, Object>) ((Map<String, Object>) a.getOrDefault("inputs", Map.of())).getOrDefault("properties", Map.of());
                    props.forEach((n, p) -> {
                        if (!id.matcher(n).matches()) problems.add(aid + " input " + n + ": snake_case");
                        if (String.valueOf(((Map<String, Object>) p).getOrDefault("description", "")).isBlank()) problems.add(aid + " input " + n + ": no description");
                    });
                }
        }
        assertEquals(List.of(), problems);
        assertTrue(pkgIds.size() >= 40, "built-in package count " + pkgIds.size());
    }
}
