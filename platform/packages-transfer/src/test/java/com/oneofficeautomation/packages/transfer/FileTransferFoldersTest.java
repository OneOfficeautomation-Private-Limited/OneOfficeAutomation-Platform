package com.oneofficeautomation.packages.transfer;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Folder actions over an in-memory remote file system that behaves like an FTP/SFTP server. */
class FileTransferFoldersTest {

    /** Remote paths are absolute ("/a/b"); folders are kept in a set, files map to their bytes. */
    static final class MemoryConn implements FileTransferPackage.Conn {
        final Set<String> dirs = new TreeSet<>(List.of("/"));
        final Map<String, byte[]> files = new TreeMap<>();
        String cwd = "/";

        String abs(String p) {
            String path = p.startsWith("/") ? p : (cwd.endsWith("/") ? cwd : cwd + "/") + p;
            Deque<String> parts = new ArrayDeque<>();
            for (String s : path.split("/")) { if (s.isEmpty() || s.equals(".")) continue; if (s.equals("..")) parts.pollLast(); else parts.addLast(s); }
            return "/" + String.join("/", parts);
        }
        String parent(String a) { int i = a.lastIndexOf('/'); return i <= 0 ? "/" : a.substring(0, i); }
        public List<Object> list(String dir) throws Exception {
            String d = abs(dir == null || dir.isBlank() ? "." : dir);
            if (!dirs.contains(d)) throw new java.io.FileNotFoundException("no such folder " + d);
            List<Object> rows = new ArrayList<>();
            for (String x : dirs) if (!x.equals(d) && parent(x).equals(d)) rows.add(new ArrayList<>(Arrays.asList(x.substring(x.lastIndexOf('/') + 1), true, BigDecimal.ZERO, null)));
            for (var e : files.entrySet()) if (parent(e.getKey()).equals(d)) rows.add(new ArrayList<>(Arrays.asList(e.getKey().substring(e.getKey().lastIndexOf('/') + 1), false, BigDecimal.valueOf(e.getValue().length), null)));
            return rows;
        }
        public void download(String remote, Path local) throws Exception {
            byte[] b = files.get(abs(remote));
            if (b == null) throw new java.io.FileNotFoundException(remote);
            Files.write(local, b);
        }
        public void upload(Path local, String remote) throws Exception {
            String a = abs(remote);
            if (!dirs.contains(parent(a))) throw new java.io.FileNotFoundException("no folder for " + a);
            files.put(a, Files.readAllBytes(local));
        }
        public void delete(String remote) throws Exception { if (files.remove(abs(remote)) == null) throw new java.io.FileNotFoundException(remote); }
        public void rename(String from, String to) { files.put(abs(to), files.remove(abs(from))); }
        public void mkdir(String dir) throws Exception {
            String a = abs(dir);
            if (dirs.contains(a)) throw new java.io.IOException("exists");
            if (!dirs.contains(parent(a))) throw new java.io.FileNotFoundException("no parent");
            dirs.add(a);
        }
        public void rmdir(String dir) throws Exception {
            String a = abs(dir);
            if (!list(a).isEmpty()) throw new java.io.IOException("not empty");
            dirs.remove(a);
        }
        public void cd(String dir) throws Exception { String a = abs(dir); if (!dirs.contains(a)) throw new java.io.FileNotFoundException(dir); cwd = a; }
        public String pwd() { return cwd; }
        public void close() { }
    }

    static ActionContext ctx(Path w, MemoryConn c) {
        Map<String, Object> sessions = new HashMap<>();
        sessions.put("transfer", c);
        return new ActionContext("run", "s", "default", w, sessions, new HashMap<>(), new AtomicBoolean(), List::of, (l, m) -> {});
    }

    @Test void changeUploadDownloadAndDeleteFolders() throws Exception {
        Path w = Files.createTempDirectory("ooa-transfer");
        Files.createDirectories(w.resolve("out/2026/q4"));
        Files.writeString(w.resolve("out/a.csv"), "a");
        Files.writeString(w.resolve("out/2026/b.csv"), "bb");
        Files.writeString(w.resolve("out/2026/q4/c.csv"), "ccc");
        MemoryConn conn = new MemoryConn();
        conn.dirs.add("/outgoing");
        ActionContext ctx = ctx(w, conn);
        FileTransferPackage p = new FileTransferPackage();

        assertEquals("/outgoing", p.changeFolder(ctx, "outgoing", "transfer").get("folder"));
        assertEquals("TRANSFER.FAILED", assertThrows(ActionException.class, () -> p.changeFolder(ctx, "/missing", "transfer")).code());

        List<?> up = (List<?>) p.uploadFolder(ctx, "out", "batch", "transfer").get("files");
        assertEquals(3, up.size());
        assertArrayEquals("ccc".getBytes(), conn.files.get("/outgoing/batch/2026/q4/c.csv"), "relative to the changed folder, sub-folders created");
        p.uploadFolder(ctx, "out", "batch", "transfer");  // second upload over existing folders works
        assertEquals(3, conn.files.size());

        List<?> down = (List<?>) p.downloadFolder(ctx, "/outgoing/batch", "in", "transfer").get("files");
        assertEquals(3, down.size());
        assertEquals("ccc", Files.readString(w.resolve("in/2026/q4/c.csv")));
        assertEquals("a", Files.readString(w.resolve("in/a.csv")));

        assertEquals("TRANSFER.NOT_EMPTY", assertThrows(ActionException.class, () -> p.deleteFolder(ctx, "/outgoing/batch", false, "transfer")).code());
        for (String bad : List.of("/", ".", "", "//", "./"))
            assertEquals("TRANSFER.PROTECTED", assertThrows(ActionException.class, () -> p.deleteFolder(ctx, bad, true, "transfer")).code(), bad);
        assertEquals(new BigDecimal(3), p.deleteFolder(ctx, "/outgoing/batch", true, "transfer").get("deleted_files"));
        assertFalse(conn.dirs.contains("/outgoing/batch"));
        assertTrue(conn.files.isEmpty());
        conn.dirs.add("/outgoing/empty");
        p.deleteFolder(ctx, "empty", false, "transfer");
        assertFalse(conn.dirs.contains("/outgoing/empty"));

        assertEquals("FILE.NOT_FOUND", assertThrows(ActionException.class, () -> p.uploadFolder(ctx, "nope", "x", "transfer")).code());
        assertEquals("TRANSFER.UNSAFE_NAME", assertThrows(ActionException.class, () -> FileTransferPackage.safeEntry("..")).code());
        assertEquals("TRANSFER.UNSAFE_NAME", assertThrows(ActionException.class, () -> FileTransferPackage.safeEntry("a/../../b")).code());
        assertEquals("dir/file", FileTransferPackage.join("dir", "file"));
        assertEquals("file", FileTransferPackage.join(".", "file"));
        assertEquals("/file", FileTransferPackage.join("/", "file"));
    }

    @Test void newActionsAreRegistered() {
        var reg = FirstPartyPackages.registry();
        assertNotNull(reg);
    }
}
