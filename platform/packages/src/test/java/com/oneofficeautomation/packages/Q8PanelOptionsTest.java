package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** Q8: zip into an existing archive, folder size and date conditions, repeated image search, typed JSON nodes and SAP context-menu entries. */
class Q8PanelOptionsTest {
    @TempDir Path tmp;

    ActionContext ctx() { return PackagesTestSupport.ctx(tmp, Map.of()); }
    static String code(Runnable r) { return assertThrows(ActionException.class, r::run).code(); }
    static void num(long expected, Object actual) { assertEquals(0, BigDecimal.valueOf(expected).compareTo((BigDecimal) actual), "got " + actual); }

    static void at(Path p, LocalDateTime t) throws Exception { Files.setLastModifiedTime(p, FileTime.from(t.atZone(ZoneId.systemDefault()).toInstant())); }

    static Map<String, String> zipContent(Path zip) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        try (ZipFile z = new ZipFile(zip.toFile())) {
            for (ZipEntry e : Collections.list(z.entries())) out.put(e.getName(), new String(z.getInputStream(e).readAllBytes()));
        }
        return out;
    }

    // ---------- File: zip into an existing archive ----------

    @Test void zipAddsToAnExistingArchiveOnlyNewer() throws Exception {
        FilePackage f = new FilePackage();
        LocalDateTime t0 = LocalDateTime.of(2026, 10, 1, 9, 0);
        Path in = Files.createDirectories(tmp.resolve("in"));
        Files.writeString(in.resolve("a.txt"), "a1");
        Files.writeString(in.resolve("b.txt"), "b1");
        at(in.resolve("a.txt"), t0);
        at(in.resolve("b.txt"), t0);
        Map<String, Object> first = f.zip(ctx(), "in", "out/x.zip", null, "normal", true, false);
        num(2, first.get("added"));

        // a.txt changes and is newer, b.txt changes but keeps its old date, c.txt is new
        Files.writeString(in.resolve("a.txt"), "a2");
        at(in.resolve("a.txt"), t0.plusHours(1));
        Files.writeString(in.resolve("b.txt"), "b2");
        at(in.resolve("b.txt"), t0);
        Files.writeString(in.resolve("c.txt"), "c1");
        Map<String, Object> upd = f.zip(ctx(), "in", "out/x.zip", null, "normal", false, false, null, true, true);
        num(2, upd.get("added"));
        assertEquals(Map.of("a.txt", "a2", "b.txt", "b1", "c.txt", "c1"), zipContent(tmp.resolve("out/x.zip")));
        assertEquals(List.of("a.txt", "b.txt", "c.txt"), new ArrayList<>(zipContent(tmp.resolve("out/x.zip")).keySet()), "kept entries keep their order, new ones are appended");

        // without only_newer every matching file replaces its entry; other entries stay
        Files.delete(in.resolve("c.txt"));
        f.zip(ctx(), "in", "out/x.zip", "b.txt", "store", false, false, null, true, false);
        assertEquals(Map.of("a.txt", "a2", "b.txt", "b2", "c.txt", "c1"), zipContent(tmp.resolve("out/x.zip")));
        try (var s = Files.list(tmp.resolve("out"))) { assertEquals(1, s.count(), "no temporary file is left behind"); }

        // a missing archive is simply created; the old signature still replaces
        num(2, f.zip(ctx(), "in", "out/new.zip", null, "normal", false, false, null, true, true).get("added"));
        f.zip(ctx(), "in", "out/x.zip", "a.txt", "normal", true, false);
        assertEquals(Set.of("a.txt"), zipContent(tmp.resolve("out/x.zip")).keySet());

        assertEquals("ZIP.BAD_OPTION", code(() -> f.zip(ctx(), "in", "out/x.zip", null, "normal", true, false, null, false, true)));
        assertEquals("ZIP.BAD_OPTION", code(() -> f.zip(ctx(), "in", "out/x.zip", null, "normal", true, false, new SecureString("pw", "t"), true, false)));
        Files.writeString(tmp.resolve("out/broken.zip"), "not a zip");
        assertEquals("ZIP.CORRUPT", code(() -> f.zip(ctx(), "in", "out/broken.zip", null, "normal", true, false, null, true, false)));
    }

    // ---------- Folder: size and date conditions ----------

    @Test void folderDeleteAndRenameWithConditions() throws Exception {
        FolderPackage fp = new FolderPackage();
        LocalDateTime old = LocalDateTime.of(2026, 1, 10, 12, 0), cut = LocalDateTime.of(2026, 6, 1, 0, 0);
        Path big = Files.createDirectories(tmp.resolve("big/sub"));
        Files.write(big.resolve("data.bin"), new byte[3 * 1024]);
        at(tmp.resolve("big"), old);

        // 3 KB in all, changed in January
        assertEquals(false, fp.delete(ctx(), "big", true, new BigDecimal("5"), null, null, null).get("deleted"), "not larger than 5 KB");
        assertEquals(false, fp.delete(ctx(), "big", true, null, null, cut, null).get("deleted"), "not changed after June");
        assertTrue(Files.isDirectory(tmp.resolve("big")));
        Map<String, Object> skipped = fp.renameFolder(ctx(), "big", "renamed", null, new BigDecimal("2"), null, null);
        assertEquals(false, skipped.get("renamed"));
        assertEquals(tmp.resolve("big").toAbsolutePath().normalize().toString(), skipped.get("path"));

        Map<String, Object> renamed = fp.renameFolder(ctx(), "big", "old-big", new BigDecimal("2"), new BigDecimal("4"), null, cut);
        assertEquals(true, renamed.get("renamed"));
        assertTrue(Files.isDirectory(tmp.resolve("old-big/sub")));
        assertEquals(true, fp.delete(ctx(), "old-big", true, new BigDecimal("1"), null, LocalDateTime.of(2025, 1, 1, 0, 0), cut).get("deleted"));
        assertFalse(Files.exists(tmp.resolve("old-big")));

        // old signatures, a missing folder, and ranges no folder can meet
        Files.createDirectories(tmp.resolve("empty"));
        assertEquals(true, fp.delete(ctx(), "empty", false).get("deleted"));
        assertEquals(false, fp.delete(ctx(), "gone", false).get("deleted"));
        Files.createDirectories(tmp.resolve("r"));
        assertEquals(true, fp.renameFolder(ctx(), "r", "r2").get("renamed"));
        assertEquals("FOLDER.BAD_OPTION", code(() -> fp.delete(ctx(), "r2", true, new BigDecimal("10"), new BigDecimal("10"), null, null)));
        assertEquals("FOLDER.BAD_OPTION", code(() -> fp.renameFolder(ctx(), "r2", "x", null, null, cut, old)));
        assertEquals("FOLDER.BAD_OPTION", code(() -> fp.delete(ctx(), "r2", true, new BigDecimal("-1"), null, null, null)));
        assertTrue(Files.isDirectory(tmp.resolve("r2")), "a refused option changes nothing");
    }

    // ---------- Image: repeated search in an image file ----------

    @Test void findInImageRepeatsUntilTheFileShowsThePicture() throws Exception {
        BufferedImage screen = new BufferedImage(200, 120, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = screen.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, 200, 120);
        g.setColor(Color.BLACK); g.fillRect(60, 40, 30, 20);
        g.setColor(Color.RED); g.fillRect(68, 46, 14, 8);
        g.dispose();
        ImageIO.write(screen.getSubimage(60, 40, 30, 20), "png", tmp.resolve("button.png").toFile());
        BufferedImage blank = new BufferedImage(200, 120, BufferedImage.TYPE_INT_RGB);
        Graphics2D b = blank.createGraphics(); b.setColor(Color.WHITE); b.fillRect(0, 0, 200, 120); b.dispose();
        ImageIO.write(blank, "png", tmp.resolve("screen.png").toFile());
        ImagePackage img = new ImagePackage();

        long start = System.nanoTime();
        Map<String, Object> miss = img.findInImage(ctx(), "screen.png", "button.png", 5, 1, "center", 0, 0, null, null, null, null, 3, 100);
        assertEquals(false, miss.get("found"));
        assertTrue((System.nanoTime() - start) / 1_000_000 >= 200, "three looks with two pauses of 100 ms");

        // another program writes the picture a moment later (to a temporary name first, then moves it in place)
        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(300);
                ImageIO.write(screen, "png", tmp.resolve("screen.tmp.png").toFile());
                Files.move(tmp.resolve("screen.tmp.png"), tmp.resolve("screen.png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        writer.start();
        Map<String, Object> hit = img.findInImage(ctx(), "screen.png", "button.png", 5, 1, "center", 0, 0, null, null, null, null, 50, 50);
        writer.join();
        assertEquals(true, hit.get("found"));
        assertEquals(75, ((BigDecimal) hit.get("x")).intValue());

        // a file that never appears fails on the last look; bad options are refused
        assertEquals("FILE.NOT_FOUND", code(() -> img.findInImage(ctx(), "nowhere.png", "button.png", 5, 1, "center", 0, 0, null, null, null, null, 2, 10)));
        assertEquals("IMAGE.BAD_REPEAT", code(() -> img.findInImage(ctx(), "screen.png", "button.png", 5, 1, "center", 0, 0, null, null, null, null, 0, 10)));
        assertEquals("IMAGE.BAD_INTERVAL", code(() -> img.findInImage(ctx(), "screen.png", "button.png", 5, 1, "center", 0, 0, null, null, null, null, 2, -1)));
    }

    // ---------- JSON: typed entries in get_nodes ----------

    @Test @SuppressWarnings("unchecked")
    void getNodesConvertsEntriesWithAType() {
        JsonPackage j = new JsonPackage();
        String doc = "{\"order\": {\"id\": \"42\", \"total\": \"1,234.50\", \"paid\": \"yes\", \"date\": \"2026-10-18\", \"note\": \"\", \"lines\": [1, 2]}}";
        Map<String, Object> paths = new LinkedHashMap<>();
        paths.put("id", Map.of("path", "order.id", "type", "Number"));
        paths.put("raw_id", "order.id");
        paths.put("total", Map.of("path", "order.total", "type", "number"));
        paths.put("paid", Map.of("path", "order.paid", "type", "Boolean"));
        paths.put("date", Map.of("path", "order.date", "type", "DateTime"));
        paths.put("note", Map.of("path", "order.note", "type", "Number"));
        paths.put("lines", Map.of("path", "order.lines", "type", "String"));
        paths.put("gone", Map.of("path", "order.nope", "type", "Number"));
        paths.put("plain", Map.of("path", "order.id"));
        Map<String, Object> r = j.getNodes(ctx(), doc, paths);
        Map<String, Object> v = (Map<String, Object>) r.get("values");
        num(42, v.get("id"));
        assertEquals("42", v.get("raw_id"));
        num(0, ((BigDecimal) v.get("total")).subtract(new BigDecimal("1234.50")));
        assertEquals(true, v.get("paid"));
        assertEquals(LocalDateTime.of(2026, 10, 18, 0, 0), v.get("date"));
        assertNull(v.get("note"), "an empty value stays empty");
        assertEquals("[1,2]", v.get("lines"));
        assertNull(v.get("gone"));
        assertEquals("42", v.get("plain"));
        assertEquals(List.of("gone"), r.get("missing"));

        // a list of entries uses each path as its name
        Map<String, Object> listed = (Map<String, Object>) j.getNodes(ctx(), doc, List.of(Map.of("path", "order.id", "type", "Number"), "order.paid")).get("values");
        num(42, listed.get("order.id"));
        assertEquals("yes", listed.get("order.paid"));

        assertEquals("JSON.CONVERT_FAILED", code(() -> j.getNodes(ctx(), doc, Map.of("x", Map.of("path", "order.paid", "type", "Number")))));
        assertEquals("JSON.CONVERT_FAILED", code(() -> j.getNodes(ctx(), doc, Map.of("x", Map.of("path", "order.id", "type", "Boolean")))));
        assertEquals("JSON.BAD_TYPE", code(() -> j.getNodes(ctx(), doc, Map.of("x", Map.of("path", "order.id", "type", "Money")))));
        assertEquals("JSON.BAD_PATH", code(() -> j.getNodes(ctx(), doc, Map.of("x", Map.of("type", "Number")))));
        assertEquals("JSON.BAD_PATH", code(() -> j.getNodes(ctx(), doc, Map.of("x", Map.of("path", "order.id", "kind", "Number")))));
    }

    // ---------- SAP: context-menu entry by text or position ----------

    @Test void sapRightClickByTextOrPosition() {
        List<Object[]> seen = new ArrayList<>();
        SapPackage.testRunner = (body, args, stdin) -> { seen.add(new Object[]{body, args, stdin}); return new HashMap<>(); };
        try {
            SapPackage sap = new SapPackage();
            String grid = "wnd[0]/usr/cntlGRID1/shellcont/shell";
            sap.rightClick(ctx(), grid, "3", "MATNR", "Copy Text", 0, 0, "text");
            Object[] s = seen.get(seen.size() - 1);
            assertEquals(List.of("0", "0", grid, "text"), s[1]);
            assertEquals("3\r\nMATNR\r\nCopy Text\r\n", s[2]);
            assertTrue(((String) s[0]).contains("e.selectContextMenuItemByText fc") && ((String) s[0]).contains("e.selectContextMenuItemByPosition fc"));
            assertTrue(((String) s[0]).contains("u.selectContextMenuItemByText fc"), "screen elements use their window's user area");

            sap.rightClick(ctx(), "wnd[1]/usr/ctxtP_MATNR", null, null, " 2 > 1 ", 0, 0, "position");
            s = seen.get(seen.size() - 1);
            assertEquals("position", ((List<?>) s[1]).get(3));
            assertEquals("\r\n\r\n2|1\r\n", s[2], "positions are sent as SAP expects them, separated by |");

            sap.rightClick(ctx(), grid, null, null, "&COPY", 0, 0);
            assertEquals("code", ((List<?>) seen.get(seen.size() - 1)[1]).get(3), "the old signature picks by function code");

            assertEquals("SAP.BAD_MENU", code(() -> sap.rightClick(ctx(), grid, null, null, "Copy", 0, 0, "position")));
            assertEquals("SAP.BAD_MENU", code(() -> sap.rightClick(ctx(), grid, null, null, "Copy", 0, 0, "label")));
            assertEquals("2|0|5", SapPackage.rightClickEntry("position", "2|0 / 5"));
            assertEquals("", SapPackage.rightClickEntry("text", "  "));
        } finally { SapPackage.testRunner = null; }
    }
}
