package com.oneofficeautomation.cli;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.OoaPackage;
import com.oneofficeautomation.sdk.ToolkitGroup;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Toolkit groups (docs/architecture/13-modules.md, phase 5): each group in platform/toolkits/<group> is found with
 * ServiceLoader, the core never names a group's toolkits, and the product still has every toolkit exactly once.
 */
class ToolkitGroupsTest {
    static final Path ROOT = ArchitectureTest.ROOT;

    /** The toolkit classes each group's sources hold (file names ending in Package.java). */
    static Map<String, Set<String>> groupSources() throws IOException {
        Map<String, Set<String>> out = new TreeMap<>();
        try (Stream<Path> dirs = Files.list(ROOT.resolve("toolkits"))) {
            for (Path g : dirs.filter(Files::isDirectory).toList()) {
                Set<String> names = new TreeSet<>();
                try (Stream<Path> s = Files.walk(g.resolve("src/main/java"))) {
                    s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith("Package.java")).forEach(n -> names.add(n.replace(".java", "")));
                }
                out.put(g.getFileName().toString(), names);
            }
        }
        return out;
    }

    @Test void everyGroupIsFoundAndListsItsOwnToolkits() throws IOException {
        Map<String, Set<String>> sources = groupSources();
        Map<String, Set<String>> found = new TreeMap<>();
        for (ToolkitGroup g : ServiceLoader.load(ToolkitGroup.class)) {
            Set<String> names = new TreeSet<>();
            for (Class<?> c : g.toolkits()) {
                assertNotNull(c.getAnnotation(OoaPackage.class), c + " in group " + g.id() + " is a toolkit");
                names.add(c.getSimpleName());
            }
            assertNull(found.put(g.id(), names), "one group per id: " + g.id());
        }
        assertEquals(sources, found, "each folder under platform/toolkits is a group that lists exactly its toolkits");
    }

    @Test void theCoreNeverNamesAGroupsToolkits() throws IOException {
        String core = Files.readString(ROOT.resolve("packages/src/main/java/com/oneofficeautomation/packages/FirstPartyPackages.java"));
        List<String> named = new ArrayList<>();
        for (Set<String> names : groupSources().values()) for (String n : names) if (core.matches("(?s).*\\b" + n + "\\b.*")) named.add(n);
        assertEquals(List.of(), named, "FirstPartyPackages finds the groups with ServiceLoader; it lists only the core toolkits");
    }

    @Test void theProductHasEveryToolkitOnce() {
        List<Class<?>> all = FirstPartyPackages.ALL;
        assertEquals(new HashSet<>(all).size(), all.size(), "no toolkit twice");
        assertEquals(FirstPartyPackages.CORE, all.subList(0, FirstPartyPackages.CORE.size()), "the core toolkits first");
        int inGroups = 0;
        for (ToolkitGroup g : ServiceLoader.load(ToolkitGroup.class)) inGroups += g.toolkits().size();
        assertEquals(FirstPartyPackages.CORE.size() + inGroups, all.size());
        assertEquals(88, all.size(), "87 first-party toolkits and the runner's core package, as before the split");
    }
}
