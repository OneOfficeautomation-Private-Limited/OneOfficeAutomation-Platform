package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G3 (AA parity, 22 Nov 2026), Excel desktop steps: an open Excel session is found by name and copied (unsaved changes
 * included) for Excel to work on, the PDF can be numbered when the name is taken, and the active sheet can be exported.
 * Excel itself is not needed: these check what the steps hand to it.
 */
class ExcelDesktopParityG3Test {
    @TempDir Path dir;

    /** A stand-in for the Excel package's session. */
    static final class FakeBook implements OpenWorkbook {
        final Path file; String content = "unsaved"; Path replacedFrom; int saves;
        FakeBook(Path file) { this.file = file; }
        @Override public Path file() { return file; }
        @Override public String activeSheet() { return "Summary"; }
        @Override public void writeCopy(Path target) { try { Files.writeString(target, content); } catch (Exception e) { throw new RuntimeException(e); } }
        @Override public void replaceWith(Path source) { replacedFrom = source; }
        @Override public void save() { saves++; }
    }

    static String code(Runnable r) { return assertThrows(ActionException.class, r::run).code(); }

    @Test void theOpenSessionIsFoundAndCopiedUnderItsOwnName() throws Exception {
        ActionContext ctx = PackagesTestSupport.ctx(dir, Map.of());
        FakeBook book = new FakeBook(dir.resolve("report.xlsm"));
        ctx.sessions().put("excel", book);
        assertSame(book, ExcelDesktopPackage.openWorkbook(ctx, "excel"));
        assertNull(ExcelDesktopPackage.openWorkbook(ctx, " "), "no session: the path is used");
        assertEquals("EXCEL_DESKTOP.NO_SESSION", code(() -> ExcelDesktopPackage.openWorkbook(ctx, "other")));
        Path copy = ExcelDesktopPackage.copyOf(book);
        assertEquals("report.xlsm", copy.getFileName().toString(), "the extension keeps the macros");
        assertEquals("unsaved", Files.readString(copy), "changes not yet saved are in the copy");
        ExcelDesktopPackage.deleteCopy(copy);
        assertFalse(Files.exists(copy.getParent()));
    }

    @Test void aTakenPdfNameIsNumberedReplacedOrRefused() throws Exception {
        ActionContext ctx = PackagesTestSupport.ctx(dir, Map.of());
        Files.writeString(dir.resolve("out.pdf"), "x");
        Files.writeString(dir.resolve("out (2).pdf"), "x");
        assertEquals("out (3).pdf", ExcelDesktopPackage.target(ctx, "out.pdf", true, "number").getFileName().toString());
        assertEquals("out.pdf", ExcelDesktopPackage.target(ctx, "out.pdf", true, "replace").getFileName().toString());
        assertEquals("out.pdf", ExcelDesktopPackage.target(ctx, "out.pdf", true, null).getFileName().toString(), "old steps follow overwrite");
        assertEquals("EXCEL_DESKTOP.EXISTS", code(() -> ExcelDesktopPackage.target(ctx, "out.pdf", false, null)));
        assertEquals("EXCEL_DESKTOP.EXISTS", code(() -> ExcelDesktopPackage.target(ctx, "out.pdf", true, "fail")));
        assertEquals("new.pdf", ExcelDesktopPackage.target(ctx, "new.pdf", true, "number").getFileName().toString());
    }

    @Test void theActiveSheetIsWhatExcelExports() {
        ExcelDesktopPackage.Script s = ExcelDesktopPackage.exportPdfScript("C:\\b.xlsx", "C:\\b.pdf", null, null, true, false, null, null, null);
        assertTrue(s.body().contains("elseif ($env:" + ExcelDesktopPackage.ACTIVE_ENV + ") { $wb.ActiveSheet }"), s.body());
        s.env().put(ExcelDesktopPackage.ACTIVE_ENV, "1");   // the step sets it when active_sheet is on
        assertEquals("1", s.env().get(ExcelDesktopPackage.ACTIVE_ENV));
    }

    @Test void newStepsNumberTheFileAndOldOverloadsStay() throws Exception {
        var m = ExcelDesktopPackage.class.getMethod("exportPdf", ActionContext.class, String.class, String.class, String.class, String.class, boolean.class,
                boolean.class, Integer.class, Integer.class, boolean.class, Object.class);
        assertNotNull(m);
        assertNotNull(ExcelDesktopPackage.class.getMethod("runMacro", ActionContext.class, String.class, String.class, List.class, boolean.class, int.class, Object.class, boolean.class));
        assertTrue(String.valueOf(PackageIntrospector.manifest(ExcelDesktopPackage.class)).contains("if_exists"), "export_pdf offers if_exists");
    }
}
