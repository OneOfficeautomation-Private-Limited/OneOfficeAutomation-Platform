package com.oneofficeautomation.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The repository's Markdown is published by GitHub Pages, which runs it through Jekyll's Liquid templates: a "{{" that
 * a "}}" does not close on the same line, or any "{%", stops the whole site from building (the pages build failed on
 * 5 Nov 2026 for one "{{}" in a toolkit description).
 */
class DocsSiteTest {
    static final Path REPO = Files.isDirectory(Path.of("../../docs")) ? Path.of("../..") : Path.of("..");

    @Test void markdownHasNoLiquidThatStopsThePagesBuild() throws IOException {
        List<String> bad = new ArrayList<>();
        try (Stream<Path> files = Files.walk(REPO)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".md")).toList()) {
                String rel = REPO.relativize(p).toString().replace('\\', '/');
                if (rel.contains("node_modules/") || rel.contains("/build/") || rel.startsWith(".claude/") || rel.startsWith(".git/")) continue;
                List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String l = lines.get(i);
                    if (l.contains("{%")) bad.add(rel + ":" + (i + 1) + " has {%");
                    for (int at = l.indexOf("{{"); at >= 0; at = l.indexOf("{{", at + 2)) {
                        int close = l.indexOf("}}", at + 2);
                        if (close < 0 || l.substring(at + 2, close).contains("{")) { bad.add(rel + ":" + (i + 1) + " has a {{ that }} does not close"); break; }
                    }
                }
            }
        }
        assertTrue(bad.isEmpty(), "Jekyll (GitHub Pages) cannot build these lines; reword them or write the braces apart:\n" + String.join("\n", bad));
    }
}
