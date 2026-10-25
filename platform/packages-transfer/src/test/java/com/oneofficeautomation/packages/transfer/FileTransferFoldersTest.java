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
        final Map<String, java.time.LocalDateTime> times = new HashMap<>();  // modified time per file
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
            for (var e : files.entrySet()) if (parent(e.getKey()).equals(d)) rows.add(new ArrayList<>(Arrays.asList(e.getKey().substring(e.getKey().lastIndexOf('/') + 1), false, BigDecimal.valueOf(e.getValue().length), times.get(e.getKey()))));
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
            times.put(a, java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
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

        List<?> up = (List<?>) p.uploadFolder(ctx, "out", "batch", "transfer", "*", true, true, false).get("files");
        assertEquals(3, up.size());
        assertArrayEquals("ccc".getBytes(), conn.files.get("/outgoing/batch/2026/q4/c.csv"), "relative to the changed folder, sub-folders created");
        p.uploadFolder(ctx, "out", "batch", "transfer", "*", true, true, false);  // second upload over existing folders works
        assertEquals(3, conn.files.size());

        List<?> down = (List<?>) p.downloadFolder(ctx, "/outgoing/batch", "in", "transfer", "*", true, true, false).get("files");
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

        assertEquals("FILE.NOT_FOUND", assertThrows(ActionException.class, () -> p.uploadFolder(ctx, "nope", "x", "transfer", "*", true, true, false)).code());
        assertEquals("TRANSFER.UNSAFE_NAME", assertThrows(ActionException.class, () -> FileTransferPackage.safeEntry("..")).code());
        assertEquals("TRANSFER.UNSAFE_NAME", assertThrows(ActionException.class, () -> FileTransferPackage.safeEntry("a/../../b")).code());
        assertEquals("dir/file", FileTransferPackage.join("dir", "file"));
        assertEquals("file", FileTransferPackage.join(".", "file"));
        assertEquals("/file", FileTransferPackage.join("/", "file"));
    }

    @Test void downloadFolderByChangeDate() throws Exception {
        Path w = Files.createTempDirectory("ooa-transfer-dates");
        MemoryConn conn = new MemoryConn();
        conn.dirs.add("/d");
        java.time.LocalDateTime now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
        conn.files.put("/d/today.csv", "t".getBytes()); conn.times.put("/d/today.csv", now);   // not a few minutes back: just after midnight UTC that would be yesterday
        conn.files.put("/d/lastweek.csv", "w".getBytes()); conn.times.put("/d/lastweek.csv", now.minusDays(6));
        conn.files.put("/d/old.csv", "o".getBytes()); conn.times.put("/d/old.csv", java.time.LocalDateTime.of(2020, 3, 1, 12, 0));
        conn.files.put("/d/notime.csv", "n".getBytes());   // the server reports no time for it
        ActionContext ctx = ctx(w, conn);
        FileTransferPackage p = new FileTransferPackage();
        java.util.function.Function<Object, List<String>> names = r -> ((List<?>) r).stream().map(x -> Path.of(String.valueOf(x)).getFileName().toString()).sorted().toList();

        assertEquals(List.of("today.csv"), names.apply(p.downloadFolder(ctx, "/d", "a", "transfer", "*", false, true, false, 0, null, null).get("files")));
        assertEquals(List.of("lastweek.csv", "today.csv"), names.apply(p.downloadFolder(ctx, "/d", "b", "transfer", "*", false, true, false, 7, null, null).get("files")));
        assertEquals(List.of("old.csv"), names.apply(p.downloadFolder(ctx, "/d", "c", "transfer", "*", false, true, false, null,
                java.time.LocalDateTime.of(2020, 1, 1, 0, 0), java.time.LocalDateTime.of(2021, 1, 1, 0, 0)).get("files")));
        assertEquals(4, ((List<?>) p.downloadFolder(ctx, "/d", "e", "transfer", "*", false, true, false, null, null, null).get("files")).size(), "no filter takes every file");
        assertEquals("TRANSFER.BAD_FILTER", assertThrows(ActionException.class, () -> p.downloadFolder(ctx, "/d", "f", "transfer", "*", false, true, false, 3,
                java.time.LocalDateTime.of(2020, 1, 1, 0, 0), null)).code());
        assertEquals("TRANSFER.BAD_FILTER", assertThrows(ActionException.class, () -> p.downloadFolder(ctx, "/d", "f", "transfer", "*", false, true, false, null,
                java.time.LocalDateTime.of(2021, 1, 1, 0, 0), java.time.LocalDateTime.of(2020, 1, 1, 0, 0))).code());
    }

    @Test void newActionsAreRegistered() {
        var reg = FirstPartyPackages.registry();
        assertNotNull(reg);
    }

    @Test @SuppressWarnings("unchecked") void folderOptionsListFilterAndDeleteFiles() throws Exception {
        Path w = Files.createTempDirectory("ooa-transfer");
        Files.createDirectories(w.resolve("out/sub"));
        Files.writeString(w.resolve("out/a.csv"), "a");
        Files.writeString(w.resolve("out/b.txt"), "b");
        Files.writeString(w.resolve("out/sub/c.csv"), "c");
        MemoryConn conn = new MemoryConn();
        ActionContext ctx = ctx(w, conn);
        FileTransferPackage p = new FileTransferPackage();

        // pattern and sub-folders
        assertEquals(List.of("r/a.csv"), p.uploadFolder(ctx, "out", "r", "transfer", "*.csv", false, true, true).get("files"));
        assertEquals(1, conn.files.size());
        // overwrite false skips files already on the server; only_newer skips files not changed since
        conn.files.put("/r/a.csv", "server".getBytes());
        assertEquals(List.of(), p.uploadFolder(ctx, "out", "r", "transfer", "a.csv", true, false, false).get("files"));
        assertEquals(List.of(), p.uploadFolder(ctx, "out", "r", "transfer", "a.csv", true, true, true).get("files"), "server copy is newer");
        conn.times.put("/r/a.csv", java.time.LocalDateTime.of(2000, 1, 1, 0, 0));
        assertEquals(List.of("r/a.csv"), p.uploadFolder(ctx, "out", "r", "transfer", "a.csv", true, true, true).get("files"));
        assertEquals("a", new String(conn.files.get("/r/a.csv")));
        p.uploadFolder(ctx, "out", "r", "transfer", "*", true, true, false);
        assertEquals(3, conn.files.size());

        // list filters
        var t = (Map<String, Object>) p.list(ctx, "/r", "transfer", "*.CSV", "files").get("table");
        assertEquals(1, ((List<?>) t.get("rows")).size());
        t = (Map<String, Object>) p.list(ctx, "/r", "transfer", null, "folders").get("table");
        assertEquals("sub", ((List<?>) ((List<?>) t.get("rows")).get(0)).get(0));

        // download: overwrite false keeps local files; only_newer compares the server's time
        Files.createDirectories(w.resolve("in"));
        Files.writeString(w.resolve("in/a.csv"), "local");
        List<?> down = (List<?>) p.downloadFolder(ctx, "/r", "in", "transfer", "*", false, false, false).get("files");
        assertEquals(1, down.size(), "only b.txt is new; sub-folders skipped");
        assertEquals("local", Files.readString(w.resolve("in/a.csv")));
        conn.times.put("/r/a.csv", java.time.LocalDateTime.of(2000, 1, 1, 0, 0));
        assertEquals(0, ((List<?>) p.downloadFolder(ctx, "/r", "in", "transfer", "a.csv", false, true, true).get("files")).size(), "local copy is newer");
        conn.times.put("/r/a.csv", java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusHours(1));
        assertEquals(1, ((List<?>) p.downloadFolder(ctx, "/r", "in", "transfer", "a.csv", false, true, true).get("files")).size());
        assertEquals("a", Files.readString(w.resolve("in/a.csv")));

        // single files
        assertEquals("FILE.EXISTS", assertThrows(ActionException.class, () -> p.download(ctx, "/r/a.csv", "in/a.csv", "transfer", false)).code());
        assertEquals("TRANSFER.EXISTS", assertThrows(ActionException.class, () -> p.upload(ctx, "out/a.csv", "/r/a.csv", "transfer", false)).code());
        p.upload(ctx, "out/b.txt", "/r/new.txt", "transfer", false);
        assertArrayEquals("b".getBytes(), conn.files.get("/r/new.txt"));

        // delete files by wildcard
        assertEquals(List.of("/r/b.txt", "/r/new.txt"), p.deleteFiles(ctx, "/r", "*.txt", "transfer").get("deleted"));
        assertTrue(conn.files.containsKey("/r/a.csv"));
        assertEquals("TRANSFER.NO_PATTERN", assertThrows(ActionException.class, () -> p.deleteFiles(ctx, "/r", " ", "transfer")).code());

        assertTrue(FileTransferPackage.wildcard("inv_??.pdf").test("INV_01.PDF"));
        assertFalse(FileTransferPackage.wildcard("inv_??.pdf").test("inv_1.pdf"));
        assertTrue(FileTransferPackage.wildcard("a+b(1).txt").test("a+b(1).txt"), "regex characters are literal");
        assertEquals("/r", FileTransferPackage.parentOf("/r/a.csv"));
        assertEquals(".", FileTransferPackage.parentOf("a.csv"));
        assertEquals("TRANSFER.BAD_OPTION", assertThrows(ActionException.class, () -> p.connect(ctx, "sftp", "localhost", 22, "u", null, null, null, null, "t2", true, true, "binary", 5)).code());
        assertEquals("TRANSFER.BAD_MODE", assertThrows(ActionException.class, () -> p.connect(ctx, "ftp", "localhost", 21, "u", null, null, null, null, "t2", true, false, "ebcdic", 5)).code());
    }
}
