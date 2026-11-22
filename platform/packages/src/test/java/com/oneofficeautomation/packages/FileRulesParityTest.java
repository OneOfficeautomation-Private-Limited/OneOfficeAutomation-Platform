package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** AA parity, files family (21 Nov 2026): many-files commands read the name-rule selection; File create/rename; Folder create choices. */
class FileRulesParityTest {
    @TempDir Path tmp;
    final FilePackage fp = new FilePackage();
    final FolderPackage folders = new FolderPackage();

    ActionContext ctx() { return PackagesTestSupport.ctx(tmp, Map.of()); }

    /** A context whose input chose files by name rules, as the runner hands them over. */
    ActionContext chosen(String input, Map<String, Object> rules) {
        ActionContext c = ctx();
        List<Path> files = FileSelection.parse(Map.of(FileSelection.KEY, rules)).resolve(c::resolvePath);
        return c.withSelectedFiles(Map.of(input, files));
    }

    static Map<String, Object> rule(String what, String op, String value) { return Map.of("what", what, "op", op, "value", value); }

    Path file(String rel, String text) throws Exception {
        Path p = tmp.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
        return p;
    }

    static void num(long expected, Object actual) { assertEquals(0, BigDecimal.valueOf(expected).compareTo((BigDecimal) actual), "got " + actual); }

    @Test void copyAndMoveTakeEveryFileTheRulesChoose() throws Exception {
        file("in/INV_1.pdf", "1"); file("in/INV_2.pdf", "2"); file("in/other.pdf", "x"); file("in/sub/INV_3.pdf", "3");
        Map<String, Object> rules = Map.of("folder", "in", "rules", List.of(rule("name", "regex", "^INV_\\d+")), "subfolders", true);
        Map<String, Object> r = fp.copy(chosen("source", rules), "ignored", "out", false, false, null, false, null, null, null, null, false);
        num(3, r.get("count"));
        assertTrue(Files.exists(tmp.resolve("out/INV_1.pdf")) && Files.exists(tmp.resolve("out/sub/INV_3.pdf")), "sub-folders below the shared folder are kept");
        assertFalse(Files.exists(tmp.resolve("out/other.pdf")));
        // again, several at once, keeping both: every copy gets its own free name
        Map<String, Object> again = fp.copy(chosen("source", rules), "ignored", "out", false, true, null, false, null, null, null, null, true);
        num(3, again.get("count"));
        assertTrue(Files.exists(tmp.resolve("out/INV_1 (2).pdf")) && Files.exists(tmp.resolve("out/sub/INV_3 (2).pdf")));
        // without keep both an existing target stops the step
        assertEquals("FILE.EXISTS", assertThrows(ActionException.class, () -> fp.copy(chosen("source", rules), "x", "out", false, false, null, false, null, null, null, null, true)).code());
        // a file as the target of several files is refused
        assertEquals("FILE.BAD_TARGET", assertThrows(ActionException.class, () -> fp.copy(chosen("source", rules), "x", "in/other.pdf", false, false)).code());

        Map<String, Object> moved = fp.move(chosen("source", Map.of("folder", "in", "rules", List.of(rule("name", "starts", "inv_")))), "x", "archive", false, false);
        num(2, moved.get("count"));
        assertFalse(Files.exists(tmp.resolve("in/INV_1.pdf")), "moved, and the name rule ignores case unless match case is on");
        assertTrue(Files.exists(tmp.resolve("archive/INV_2.pdf")));
    }

    @Test void deleteListZipPrintAndLoopUseTheRules() throws Exception {
        file("d/a.tmp", "aaaa"); file("d/b.tmp", "b"); file("d/keep.txt", "k");
        Map<String, Object> tmpFiles = Map.of("folder", "d", "rules", List.of(rule("type", "in", "tmp")));
        Map<String, Object> listed = fp.list(chosen("folder", tmpFiles), "d", "*", false, null, null, null, null, "size", true);
        assertEquals(List.of(tmp.resolve("d/a.tmp").toString(), tmp.resolve("d/b.tmp").toString()), listed.get("files"), "largest first");

        Path zip = tmp.resolve("z/tmp.zip");
        num(2, fp.zip(chosen("source", tmpFiles), "d/a.tmp", zip.toString(), null, "normal", true, false).get("added"));
        try (ZipFile z = new ZipFile(zip.toFile())) { assertNotNull(z.getEntry("a.tmp")); assertNotNull(z.getEntry("b.tmp")); assertNull(z.getEntry("keep.txt")); }

        @SuppressWarnings("unchecked") List<Object> each = (List<Object>) fp.filesInFolder(chosen("folder", tmpFiles), "d", "*", true);
        assertEquals(2, each.size());
        @SuppressWarnings("unchecked") Map<String, Object> first = (Map<String, Object>) each.get(0);
        assertEquals("a.tmp", first.get("name")); assertEquals("tmp", first.get("extension")); assertEquals("a", first.get("name_without_extension"));
        num(4, first.get("size_bytes"));
        assertTrue(first.get("modified") instanceof LocalDateTime);
        assertEquals(List.of(tmp.resolve("d/a.tmp").toString(), tmp.resolve("d/b.tmp").toString(), tmp.resolve("d/keep.txt").toString()),
                fp.filesInFolder(ctx(), "d", "*"), "without rules or details: the paths, as before");

        Map<String, Object> del = fp.delete(chosen("path", tmpFiles), "d/a.tmp", null, false, null, null, null, null, null, true);
        num(2, del.get("deleted"));
        assertEquals(2, ((List<?>) del.get("files")).size());
        assertTrue(Files.exists(tmp.resolve("d/keep.txt")));
        // one exact path, as before
        num(1, fp.delete(ctx(), "d/keep.txt", null, false, null, null, null).get("deleted"));
    }

    @Test void createAndRenameFiles() throws Exception {
        Map<String, Object> r = fp.create(ctx(), "notes/a.txt", null, null, null);
        assertEquals(tmp.resolve("notes/a.txt").toString(), r.get("path"));
        assertEquals("", Files.readString(tmp.resolve("notes/a.txt")));
        assertEquals("FILE.EXISTS", assertThrows(ActionException.class, () -> fp.create(ctx(), "notes/a.txt", "fail", null, null)).code());
        assertEquals(false, fp.create(ctx(), "notes/a.txt", "keep", "new", null).get("created"));
        assertEquals(tmp.resolve("notes/a (2).txt").toString(), fp.create(ctx(), "notes/a.txt", "keep_both", "two", null).get("path"));
        fp.create(ctx(), "notes/a.txt", "replace", "replaced", "utf-8");
        assertEquals("replaced", Files.readString(tmp.resolve("notes/a.txt")));
        assertEquals("FILE.BAD_OPTION", assertThrows(ActionException.class, () -> fp.create(ctx(), "notes/b.txt", "merge", null, null)).code());

        assertEquals(tmp.resolve("notes/b.txt").toString(), fp.rename(ctx(), "notes/a.txt", "b.txt", false).get("path"));
        assertFalse(Files.exists(tmp.resolve("notes/a.txt")));
        assertEquals("FILE.EXISTS", assertThrows(ActionException.class, () -> fp.rename(ctx(), "notes/a (2).txt", "b.txt", false)).code());
        fp.rename(ctx(), "notes/a (2).txt", "b.txt", true);
        assertEquals("two", Files.readString(tmp.resolve("notes/b.txt")));
        assertEquals("FILE.BAD_NAME", assertThrows(ActionException.class, () -> fp.rename(ctx(), "notes/b.txt", "../c.txt", false)).code());
        assertEquals("FILE.NOT_FOUND", assertThrows(ActionException.class, () -> fp.rename(ctx(), "notes/none.txt", "c.txt", false)).code());
    }

    @Test void fileDateRangesUnitsAndExtensionLists() throws Exception {
        Path p = file("dated.txt", "x");
        LocalDateTime at = LocalDateTime.of(2026, 5, 10, 12, 0);
        Files.setLastModifiedTime(p, FileTime.from(at.toInstant(ZoneOffset.UTC)));
        assertTrue(fp.dateIs(ctx(), "dated.txt", "modified", "between", at.minusDays(1), null, at.plusDays(1), false, null));
        assertFalse(fp.dateIs(ctx(), "dated.txt", "modified", "between", at, null, at.plusDays(1), false, null), "exclusive range");
        assertTrue(fp.dateIs(ctx(), "dated.txt", "modified", "between", at, null, at.plusDays(1), true, null), "inclusive range");
        assertFalse(fp.dateIs(ctx(), "dated.txt", "modified", "before", at, null));
        assertTrue(fp.dateIs(ctx(), "dated.txt", "modified", "before", at, null, null, true, null), "before, inclusive");
        Files.setLastModifiedTime(p, FileTime.fromMillis(System.currentTimeMillis() - 2 * 3_600_000L));
        assertTrue(fp.dateIs(ctx(), "dated.txt", "modified", "older_than_days", null, BigDecimal.ONE, null, false, "hours"));
        assertFalse(fp.dateIs(ctx(), "dated.txt", "modified", "older_than_days", null, BigDecimal.ONE, null, false, "days"));
        assertEquals("FILE.BAD_OPTION", assertThrows(ActionException.class, () -> fp.dateIs(ctx(), "dated.txt", "modified", "older_than_days", null, BigDecimal.ONE, null, false, "weeks")).code());
        assertEquals("FILE.BAD_OPTION", assertThrows(ActionException.class, () -> fp.dateIs(ctx(), "dated.txt", "modified", "between", at, null, at.minusDays(1), false, null)).code());

        assertTrue(fp.hasExtension(ctx(), "a/report.PDF", "[\"xlsx\",\"pdf\"]"), "a List variable arrives as JSON text");
        assertTrue(fp.hasExtension(ctx(), "a/report.pdf", "xlsx; .pdf"));
        assertFalse(fp.hasExtension(ctx(), "a/report.doc", "[\"xlsx\",\"pdf\"]"));
    }

    @Test void folderCreateChoicesAndCopyByRules() throws Exception {
        Path f = tmp.resolve("Reports");
        assertEquals(true, folders.create(ctx(), "Reports", false, null).get("created"));
        file("Reports/old.txt", "x");
        assertEquals(false, folders.create(ctx(), "Reports", false, "use").get("created"));
        assertEquals("FOLDER.EXISTS", assertThrows(ActionException.class, () -> folders.create(ctx(), "Reports", false, "fail")).code());
        assertEquals("FOLDER.EXISTS", assertThrows(ActionException.class, () -> folders.create(ctx(), "Reports", true)).code(), "the old fail_if_exists still works");
        assertEquals(tmp.resolve("Reports (2)").toString(), folders.create(ctx(), "Reports", false, "numbered").get("path"));
        folders.create(ctx(), "Reports", false, "replace");
        assertTrue(Files.isDirectory(f));
        assertFalse(Files.exists(f.resolve("old.txt")), "replace leaves an empty folder");
        assertEquals("FOLDER.PROTECTED", assertThrows(ActionException.class, () -> folders.create(ctx(), tmp.toString(), false, "replace")).code());

        file("src/a/one.csv", "1"); file("src/a/two.txt", "2"); file("src/b/three.csv", "3");
        Map<String, Object> csvs = Map.of("folder", "src", "rules", List.of(rule("name", "ends", ".csv")), "subfolders", true);
        num(2, folders.copy(chosen("source", csvs), "src", "dst", false, null, false, null, null, null, null).get("copied"));
        assertTrue(Files.exists(tmp.resolve("dst/a/one.csv")) && Files.exists(tmp.resolve("dst/b/three.csv")));
        assertFalse(Files.exists(tmp.resolve("dst/a/two.txt")));
    }

    static ActionSpec.InputSpec input(Class<?> cls, String kind, String id, String name) {
        PackageIntrospector.PackageInfo info = PackageIntrospector.inspect(cls);
        ActionSpec spec = (kind.equals("action") ? info.actions() : kind.equals("condition") ? info.conditions() : info.iterators()).get(id);
        return spec.inputs().stream().filter(i -> i.name().equals(name)).findFirst().orElseThrow();
    }

    @Test void saferDefaultsAreForNewStepsOnly() {
        assertEquals("auto", input(FilePackage.class, "action", "read_text", "encoding").newStepDefault());
        assertEquals("utf-8", input(FilePackage.class, "action", "read_text", "encoding").defaultValue(), "saved steps keep reading UTF-8");
        assertEquals(false, input(FilePackage.class, "action", "unzip", "overwrite").newStepDefault());
        assertEquals(true, input(FilePackage.class, "action", "unzip", "overwrite").defaultValue());
        assertEquals(false, input(FolderPackage.class, "action", "copy", "overwrite").newStepDefault());
        assertEquals("use", input(FolderPackage.class, "action", "create", "if_exists").newStepDefault());
        // every new input is placed on its panel (the toolkit would not load otherwise)
        assertNotNull(PackageIntrospector.manifest(FilePackage.class));
        assertNotNull(PackageIntrospector.manifest(FolderPackage.class));
    }
}
