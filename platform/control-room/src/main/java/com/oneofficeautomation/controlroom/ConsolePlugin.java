package com.oneofficeautomation.controlroom;

import java.util.*;

/**
 * A page an optional module adds to the Command Center, found on the class path with {@link ServiceLoader}: Document
 * Automation first (AI > Document Automation, as in AA's Control Room). The console keeps the menu entry whether or
 * not the module is there; without it, the page says what the module does and that it is not installed. A module
 * lists itself in {@code META-INF/services/com.oneofficeautomation.controlroom.ConsolePlugin} and serves its own
 * pages under {@link #path()}. On premises the module ships with the install; in the cloud a tenant gets it when its
 * licence allows (the same class path for every tenant of a region).
 */
public interface ConsolePlugin {
    /** The slot it fills, such as {@code document-automation}. */
    String id();

    /** Its name in the menu and on its page. */
    String name();

    /** One sentence on what it does. */
    String description();

    /** Where its own page is served, such as {@code /plugins/document-automation/}. */
    String path();

    /** The modules installed, as the console lists them (GET /api/plugins). */
    static List<Map<String, Object>> installed() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ConsolePlugin p : ServiceLoader.load(ConsolePlugin.class))
            out.add(Map.of("id", p.id(), "name", p.name(), "description", p.description(), "path", p.path()));
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("id"))));
        return out;
    }
}
