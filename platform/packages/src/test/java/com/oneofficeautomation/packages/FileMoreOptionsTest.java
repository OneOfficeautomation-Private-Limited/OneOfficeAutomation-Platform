package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** File copy and move of many files by pattern with size and date filters. */
class FileMoreOptionsTest {
    @TempDir Path tmp;
    final FilePackage fp = new FilePackage();

    ActionContext ctx() { return PackagesTestSupport.ctx(tmp, Map.of()); }

    static final LocalDateTime OLD = LocalDateTime.of(2026, 1, 10, 12, 0), NEW = LocalDateTime.of(2026, 10, 20, 12, 0);

    Path file(String rel, int bytes, LocalDateTime modified) throws Exception {
        Path p = tmp.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.write(p, new byte[bytes]);
        Files.setLastModifiedTime(p, FileTime.from(modified.atZone(ZoneId.systemDefault()).toInstant()));
        return p;
    }

    static void num(long expected, Object actual) { assertEquals(0, BigDecimal.valueOf(expected).compareTo((BigDecimal) actual), "got " + actual); }

    @Test void copyManyFilesWithSizeAndDateFilters() throws Exception {
        file("in/a.pdf", 10, OLD);
        file("in/b.pdf", 500, NEW);
        file("in/c.pdf", 5000, NEW);
        file("in/d.txt", 500, NEW);
        file("in/sub/e.pdf", 600, NEW);
        Map<String, Object> r = fp.copy(ctx(), "in", "out", false, false, "*.pdf", false, 100L, 1000L, null, null);
        num(1, r.get("count"));
        assertEquals(List.of(tmp.resolve("out/b.pdf").toString()), r.get("files"));
        assertEquals(tmp.resolve("out").toString(), r.get("path"));
        assertTrue(Files.exists(tmp.resolve("in/b.pdf")), "copy keeps the source");

        Map<String, Object> dated = fp.copy(ctx(), "in", "out2", false, false, "*.pdf", true, null, null, LocalDateTime.of(2026, 6, 1, 0, 0), null);
        num(3, dated.get("count"));
        assertTrue(Files.exists(tmp.resolve("out2/sub/e.pdf")), "sub-folders are kept under the target");
        assertFalse(Files.exists(tmp.resolve("out2/a.pdf")), "an old file is left out");

        Map<String, Object> before = fp.copy(ctx(), tmp.resolve("in") + java.io.File.separator + "*.pdf", "out3", false, false, null, false, null, null, null, LocalDateTime.of(2026, 6, 1, 0, 0));
        num(1, before.get("count"));
        assertTrue(Files.exists(tmp.resolve("out3/a.pdf")), "a wildcard in the source works like a pattern");

        // one file: the old behaviour and signature
        Map<String, Object> one = fp.copy(ctx(), "in/a.pdf", "single/a.pdf", false, false);
        assertEquals(tmp.resolve("single/a.pdf").toString(), one.get("path"));
        num(1, one.get("count"));
        // an existing target with keep_both gets a free name
        Map<String, Object> again = fp.copy(ctx(), "in", "out", false, true, "b.pdf", false, null, null, null, null);
        assertEquals(List.of(tmp.resolve("out/b (2).pdf").toString()), again.get("files"));
    }

    @Test void moveManyFilesAndErrors() throws Exception {
        file("m/x.log", 50, OLD);
        file("m/y.log", 50, NEW);
        Map<String, Object> r = fp.move(ctx(), "m", "archive", false, false, "*.log", false, null, null, null, LocalDateTime.of(2026, 6, 1, 0, 0));
        num(1, r.get("count"));
        assertTrue(Files.exists(tmp.resolve("archive/x.log")));
        assertFalse(Files.exists(tmp.resolve("m/x.log")), "moved");
        assertTrue(Files.exists(tmp.resolve("m/y.log")), "a newer file stays");

        Path target = file("t.txt", 1, NEW);
        assertEquals("FILE.BAD_TARGET", assertThrows(ActionException.class, () -> fp.copy(ctx(), "m", target.toString(), false, false, "*", false, null, null, null, null)).code());
        assertEquals("FILE.BAD_OPTION", assertThrows(ActionException.class, () -> fp.copy(ctx(), "m", "o", false, false, "*", false, 10L, 5L, null, null)).code());
        assertEquals("FILE.BAD_OPTION", assertThrows(ActionException.class, () -> fp.copy(ctx(), "m", "o", false, false, "*", false, null, null, NEW, OLD)).code());
        assertEquals("FILE.NOT_FOUND", assertThrows(ActionException.class, () -> fp.copy(ctx(), "nowhere", "o", false, false, "*", false, null, null, null, null)).code());
        file("m2/y.log", 5, NEW);
        file("archive2/y.log", 5, NEW);
        assertEquals("FILE.EXISTS", assertThrows(ActionException.class, () -> fp.move(ctx(), "m2", "archive2", false, false, "*.log", false, null, null, null, null)).code());
        assertEquals(tmp.resolve("renamed.log").toString(), fp.move(ctx(), "m2/y.log", "renamed.log", false, false).get("path"), "old signature");
    }

    @Test void readTextDetectsTheEncodingAndTrims() throws Exception {
        String text = "Gr\u00fc\u00dfe \u20ac";
        Path u8 = tmp.resolve("u8.txt"), bom = tmp.resolve("bom.txt"), le = tmp.resolve("le.txt"), be = tmp.resolve("be.txt"), ansi = tmp.resolve("ansi.txt");
        Files.write(u8, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(bom, concat(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Files.write(le, concat(new byte[]{(byte) 0xFF, (byte) 0xFE}, text.getBytes(java.nio.charset.StandardCharsets.UTF_16LE)));
        Files.write(be, concat(new byte[]{(byte) 0xFE, (byte) 0xFF}, text.getBytes(java.nio.charset.StandardCharsets.UTF_16BE)));
        Files.write(ansi, text.getBytes(java.nio.charset.Charset.forName("windows-1252")));
        for (Path p : List.of(u8, bom, le, be, ansi)) assertEquals(text, fp.readText(ctx(), p.toString(), "auto", null).get("text"), p.getFileName().toString());
        // utf-8 as before
        assertEquals(text, fp.readText(ctx(), u8.toString(), "utf-8", "none").get("text"));
        Path sp = tmp.resolve("sp.txt");
        Files.writeString(sp, "  hello \n");
        assertEquals("  hello \n", fp.readText(ctx(), sp.toString(), null, null).get("text"));
        assertEquals("hello \n", fp.readText(ctx(), sp.toString(), null, "leading").get("text"));
        assertEquals("  hello", fp.readText(ctx(), sp.toString(), null, "trailing").get("text"));
        assertEquals("hello", fp.readText(ctx(), sp.toString(), "auto", "both").get("text"));
        assertEquals("FILE.BAD_OPTION", assertThrows(ActionException.class, () -> fp.readText(ctx(), sp.toString(), null, "middle")).code());
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    @Test void readVariablesTrimSidesAndLowerCaseNames() throws Exception {
        Path f = tmp.resolve("vars.ini");
        Files.writeString(f, "Server =  db1 \nPORT= 1433\n");
        @SuppressWarnings("unchecked") Map<String, Object> both = (Map<String, Object>) fp.readVariables(ctx(), f.toString(), "=", null, true, null, false).get("values");
        assertEquals(Map.of("Server", "db1", "PORT", "1433"), both);
        @SuppressWarnings("unchecked") Map<String, Object> lead = (Map<String, Object>) fp.readVariables(ctx(), f.toString(), "=", null, true, "leading", true).get("values");
        assertEquals("db1 ", lead.get("server "));
        assertEquals("1433", lead.get("port"));
        @SuppressWarnings("unchecked") Map<String, Object> trail = (Map<String, Object>) fp.readVariables(ctx(), f.toString(), "=", null, true, "trailing", false).get("values");
        assertEquals("  db1", trail.get("Server"));
        // trim off keeps everything, whatever trim_side says
        @SuppressWarnings("unchecked") Map<String, Object> none = (Map<String, Object>) fp.readVariables(ctx(), f.toString(), "=", "auto", false, "both", false).get("values");
        assertEquals("  db1 ", none.get("Server "));
        assertEquals("FILE.BAD_OPTION", assertThrows(ActionException.class, () -> fp.readVariables(ctx(), f.toString(), "=", null, true, "middle", false)).code());
    }
}
