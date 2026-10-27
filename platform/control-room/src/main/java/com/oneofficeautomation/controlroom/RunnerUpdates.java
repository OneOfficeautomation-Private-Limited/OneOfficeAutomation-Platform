package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.sdk.OoaException;

import java.io.File;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Runner updates from the Command Center (R6). The Command Center offers the program files of its own installation
 * ({@code lib}, without the vendor's licence tools) with their SHA-256 hashes; a build id names that set. A runner
 * reports the build id of its own files with each heartbeat. When they differ and either the setting
 * {@code runner_auto_update} is on or an administrator asked for that runner (Runners > Update), an idle runner is told
 * to update: it downloads the files it lacks, checks every hash, swaps its program folder and starts again. The bundled
 * Java is not updated this way. In a development build (no lib folder) nothing is offered.
 */
public final class RunnerUpdates {
    private final ControlRoom cr;
    private final Db db;
    private volatile Map<String, Object> manifest;
    /** Where the program files are; tests point it at a folder. */
    volatile Path libOverride;

    /** Offers the program files of this folder instead of the installation's (tests). */
    public void useLib(Path lib) { libOverride = lib; manifest = null; }

    RunnerUpdates(ControlRoom cr) { this.cr = cr; this.db = cr.db; }

    /** The installation's lib folder, or null outside an installation (a build from source). */
    Path lib() {
        if (libOverride != null) return libOverride;
        for (String e : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            Path p = Path.of(e.replace("*", "x")).toAbsolutePath().getParent();
            if (p != null && p.getFileName() != null && p.getFileName().toString().equals("lib") && Files.isDirectory(p)) return p;
        }
        return null;
    }

    /** {build, version, files: [{name, sha256, size}]}, or null when there is nothing to offer. Computed once. */
    public Map<String, Object> manifest() {
        Map<String, Object> m = manifest;
        if (m != null) return m;
        Path lib = lib();
        if (lib == null) return null;
        try (var s = Files.list(lib)) {
            List<Map<String, Object>> files = new ArrayList<>();
            StringBuilder all = new StringBuilder();
            String version = "";
            for (Path f : s.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList()) {
                String name = f.getFileName().toString();
                if (name.startsWith("vendor-tools")) continue;   // the vendor's licence tools never go to runners
                String sha = sha256(f);
                files.add(Map.of("name", name, "sha256", sha, "size", Files.size(f)));
                all.append(name).append(':').append(sha).append('\n');
                if (name.matches("cli-.+\\.jar")) version = name.substring(4, name.length() - 4);
            }
            if (files.isEmpty()) return null;
            m = Map.of("build", Security.sha256(all.toString()).substring(0, 16), "version", version, "files", files);
            manifest = m;
            return m;
        } catch (Exception e) { return null; }
    }

    static String sha256(Path f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(f)) {
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /** For a heartbeat: the update instruction for this runner, or null. Clears a request once the runner is current. */
    Map<String, Object> forHeartbeat(Map<String, Object> device, Object runnerBuild, boolean busy) {
        Map<String, Object> m = manifest();
        if (m == null || runnerBuild == null || String.valueOf(runnerBuild).isBlank()) return null;
        boolean requested = Boolean.TRUE.equals(device.get("update_requested"));
        if (m.get("build").equals(runnerBuild)) {
            if (requested) db.update("UPDATE devices SET update_requested = FALSE WHERE id = ?", device.get("id"));
            return null;
        }
        if (busy || !(requested || cr.platform.intSetting("runner_auto_update") == 1)) return null;
        return Map.of("build", m.get("build"), "version", m.get("version"), "files", ((List<?>) m.get("files")).size());
    }

    /** One program file for a runner, by its name in the manifest only. */
    public byte[] file(String name) throws java.io.IOException {
        Map<String, Object> m = manifest();
        if (m == null) throw new OoaException("CR.NOT_FOUND", "This Command Center offers no runner updates");
        for (Object o : (List<?>) m.get("files")) {
            if (((Map<?, ?>) o).get("name").equals(name)) return Files.readAllBytes(lib().resolve(name));
        }
        throw new OoaException("CR.NOT_FOUND", "No program file called " + name);
    }

    /** An administrator asks runners to update at their next heartbeat (when idle). */
    public Map<String, Object> request(User by, List<String> deviceIds, String ip) {
        cr.require(by, "devices.manage", "update runners");
        if (manifest() == null) throw new OoaException("CR.BAD_INPUT", "This Command Center is not an installation, so it has no runner update to offer");
        int n = 0;
        for (String id : deviceIds) n += db.update("UPDATE devices SET update_requested = TRUE WHERE id = ?", id);
        cr.audit.record(by.username(), "device.update_requested", "device", String.join(",", deviceIds), Map.of("build", manifest().get("build")), "ok", ip);
        return Map.of("requested", n, "build", manifest().get("build"), "version", manifest().get("version"));
    }
}
