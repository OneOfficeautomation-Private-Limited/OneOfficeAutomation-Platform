package com.oneofficeautomation.packages.office;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.OoaException;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

/** T1: steps that keep the default session name use the one open workbook, whatever it was opened as. */
class OpenSessionTest {

    @Test void stepsFindTheOnlyOpenWorkbookAndAskWhenSeveralAreOpen() throws Exception {
        Path dir = Files.createTempDirectory("ooa-open-session");
        for (String f : new String[] { "a.xlsx", "b.xlsx" })
            try (XSSFWorkbook wb = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(dir.resolve(f))) {
                wb.createSheet("S").createRow(0).createCell(0).setCellValue(f);
                wb.write(out);
            }
        ActionContext ctx = OfficeTestSupport.ctx(dir);
        ExcelPackage xl = new ExcelPackage();
        xl.open(ctx, "a.xlsx", "report", false);

        // the step left the session as "excel": the workbook opened as "report" is used
        assertEquals("a.xlsx", xl.getCell(ctx, "A1", "S", "excel").get("value"));
        assertEquals("a.xlsx", xl.getCell(ctx, "A1", "S", "").get("value"));

        // with two open, a step without a name must say which
        xl.open(ctx, "b.xlsx", "second", false);
        OoaException e = assertThrows(OoaException.class, () -> xl.getCell(ctx, "A1", "S", "excel"));
        assertTrue(e.getMessage().contains("report") && e.getMessage().contains("second"), e.getMessage());
        assertEquals("b.xlsx", xl.getCell(ctx, "A1", "S", "second").get("value"), "named sessions work as before");

        // closing by the default name closes the only one left
        xl.close(ctx, "second");
        xl.close(ctx, "excel");
        assertTrue(ctx.sessions().isEmpty(), ctx.sessions().keySet().toString());
    }
}
