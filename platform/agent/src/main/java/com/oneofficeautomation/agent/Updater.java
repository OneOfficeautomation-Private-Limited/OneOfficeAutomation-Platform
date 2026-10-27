package com.oneofficeautomation.agent;

import com.oneofficeautomation.sdk.OoaException;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * The runner's side of updates from the Command Center. The build id of the runner's program files goes with every
 * heartbeat; when the Command Center answers with an update and the runner is idle, it fetches the Command Center's file
 * list, takes the files it already has from its own lib folder, downloads the others and checks every SHA-256, all into
 * update/lib beside the installation. A helper process ({@link UpdateHelper}) then waits for the runner to exit, swaps
 * the folders and, for a runner started at sign-in, starts its task again; a runner service is started again by Windows
 * (it restarts after a failure). Windows only; a build from source has no lib folder
 * and never updates.
 */
final class Updater {
    private Updater() { }

    /** Reads the Command Center's file list and files (the runner's client, or a test double). */
    interface Source {
        Map<String, Object> manifest();
        byte[] file(String name);
    }

    /** The installation's lib folder, or null outside an installation. */
    static Path lib() {
        for (String e : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            Path p = Path.of(e.replace("*", "x")).toAbsolutePath().getParent();
            if (p != null && p.getFileName() != null && p.getFileName().toString().equals("lib") && Files.isDirectory(p)) return p;
        }
        return null;
    }

    /** The build id of a lib folder: the same calculation as the Command Center's (jar names and SHA-256, licence tools left out). */
    static String build(Path lib) {
        if (lib == null) return null;
        try (var s = Files.list(lib)) {
            StringBuilder all = new StringBuilder();
            for (Path f : s.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList()) {
                String name = f.getFileName().toString();
                if (name.startsWith("vendor-tools")) continue;
                all.append(name).append(':').append(sha256(Files.readAllBytes(f))).append('\n');
            }
            return all.isEmpty() ? null : sha256(all.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 16);
        } catch (Exception e) { return null; }
    }

    static String sha256(byte[] b) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /**
     * Puts the Command Center's program files into update/lib beside the installation; returns that folder. Files the runner
     * already has are copied; others downloaded; a file whose hash does not match stops the update.
     */
    @SuppressWarnings("unchecked")
    static Path stage(Path lib, Source source) throws Exception {
        Map<String, Object> m = source.manifest();
        List<Map<String, Object>> files = (List<Map<String, Object>>) m.get("files");
        if (files == null || files.isEmpty()) throw new OoaException("AGENT.UPDATE", "The Command Center sent an empty file list");
        Path target = lib.getParent().resolve("update").resolve("lib");
        if (Files.exists(target.getParent())) deleteTree(target.getParent());
        Files.createDirectories(target);
        for (Map<String, Object> f : files) {
            String name = (String) f.get("name"), sha = (String) f.get("sha256");
            if (!name.matches("[A-Za-z0-9._+-]+\\.jar")) throw new OoaException("AGENT.UPDATE", "Unexpected file name " + name);
            Path mine = lib.resolve(name);
            byte[] content = Files.exists(mine) && sha256(Files.readAllBytes(mine)).equals(sha) ? Files.readAllBytes(mine) : source.file(name);
            if (!sha256(content).equals(sha)) { deleteTree(target.getParent()); throw new OoaException("AGENT.UPDATE", "The downloaded " + name + " does not match its checksum; update stopped"); }
            Files.write(target.resolve(name), content);
        }
        if (!String.valueOf(m.get("build")).equals(build(target))) { deleteTree(target.getParent()); throw new OoaException("AGENT.UPDATE", "The staged files do not make the announced build"); }
        return target;
    }

    /**
     * Starts the helper that finishes the update once this process has ended ({@link UpdateHelper}), from a copy of this
     * jar in the temp folder so it holds nothing in the program folder; the caller then exits.
     */
    static void launch(Path home, String task) throws Exception {
        Path jar = Path.of(UpdateHelper.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path copy = Files.createTempDirectory("ooa-update").resolve("ooa-update-helper.jar");
        Files.copy(jar, copy);
        String java = ProcessHandle.current().info().command().orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        List<String> cmd = new ArrayList<>(List.of(java.replaceAll("java\\.exe$", "javaw.exe"), "-cp", copy.toString(), UpdateHelper.class.getName(),
            home.toAbsolutePath().toString(), String.valueOf(ProcessHandle.current().pid())));
        if (task != null) cmd.add(task);
        new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    }

    static void deleteTree(Path p) throws Exception {
        if (!Files.exists(p)) return;
        try (var w = Files.walk(p)) { for (Path x : w.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(x); }
    }
}
