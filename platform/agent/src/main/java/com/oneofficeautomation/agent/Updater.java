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
 * update/lib beside the installation. A small helper then waits for the runner to exit, swaps the folders (keeping
 * the old one until the new one is in place) and, for a runner started at sign-in, starts its task again; a runner
 * service is started again by Windows (it restarts after a failure). Windows only; a build from source has no lib folder
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

    /** The helper that swaps the folders once process {@code pid} has ended, then starts the runner's task again if named. */
    static String applyScript(Path home, long pid, String task) {
        String h = home.toAbsolutePath().toString();
        return String.join("\r\n",
            "@echo off",
            "rem ONEOFFICEAUTOMATION runner update: waits for the runner to stop, then swaps the program files",
            ":wait",
            "tasklist /FI \"PID eq " + pid + "\" 2>nul | find \"" + pid + "\" >nul && (timeout /t 1 /nobreak >nul & goto wait)",
            "if exist \"" + h + "\\lib.old\" rmdir /s /q \"" + h + "\\lib.old\"",
            "move \"" + h + "\\lib\" \"" + h + "\\lib.old\" >nul || exit /b 1",
            "move \"" + h + "\\update\\lib\" \"" + h + "\\lib\" >nul || (move \"" + h + "\\lib.old\" \"" + h + "\\lib\" >nul & exit /b 1)",
            "rmdir /s /q \"" + h + "\\lib.old\"",
            "rmdir /s /q \"" + h + "\\update\" 2>nul",
            task == null ? "rem no task to start: a service is started again by Windows" : "schtasks /run /tn \"" + task + "\" >nul",
            "") ;
    }

    /** Writes and starts the helper; the caller then exits. */
    static void launch(Path home, String task) throws Exception {
        Path script = home.resolve("update").resolve("apply-update.cmd");
        Files.writeString(script, applyScript(home, ProcessHandle.current().pid(), task));
        new ProcessBuilder("cmd.exe", "/c", "start", "\"ooa-update\"", "/min", "cmd.exe", "/c", script.toString())
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    }

    static void deleteTree(Path p) throws Exception {
        if (!Files.exists(p)) return;
        try (var w = Files.walk(p)) { for (Path x : w.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(x); }
    }
}
