package com.oneofficeautomation.packages.office;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.SecureString;
import com.sun.net.httpserver.HttpServer;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/** X2: a workbook on OneDrive or SharePoint (onedrive: paths) is downloaded on open and uploaded back on save. */
class CloudWorkbookTest {

    @Test void openEditAndSaveBackThroughGraph() throws Exception {
        Map<String, byte[]> drive = new ConcurrentHashMap<>();
        List<String> auth = new ArrayList<>();
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.createSheet("Sales").createRow(0).createCell(0).setCellValue("start");
            wb.write(out);
            drive.put("Reports/2026/sales.xlsx", out.toByteArray());
        }
        HttpServer graph = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        graph.createContext("/v1.0/me/drive/root:/", ex -> {
            auth.add(ex.getRequestHeaders().getFirst("Authorization"));
            String raw = ex.getRequestURI().getRawPath();
            String path = URLDecoder.decode(raw.substring("/v1.0/me/drive/root:/".length(), raw.length() - ":/content".length()), StandardCharsets.UTF_8);
            if (ex.getRequestMethod().equals("GET")) {
                byte[] b = drive.get(path);
                if (b == null) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
                ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close();
            } else {
                drive.put(path, ex.getRequestBody().readAllBytes());
                byte[] r = "{\"id\":\"item-1\",\"webUrl\":\"https://contoso.sharepoint.com/x\"}".getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, r.length); ex.getResponseBody().write(r); ex.close();
            }
        });
        graph.start();
        String base = "http://127.0.0.1:" + graph.getAddress().getPort() + "/v1.0";
        Path dir = Files.createTempDirectory("ooa-cloud-xl");
        ActionContext ctx = OfficeTestSupport.ctx(dir);
        ExcelPackage xl = new ExcelPackage();
        SecureString token = new SecureString("graph-token", "m365/graph/token");
        try {
            assertEquals("EXCEL.NO_TOKEN", assertThrows(ActionException.class, () -> xl.open(ctx, "onedrive:Reports/2026/sales.xlsx", "excel", false, null, false, null, true, null, null, base)).code());
            assertThrows(ActionException.class, () -> ExcelPackage.cloudPath("onedrive:../secret.xlsx"));

            xl.open(ctx, "onedrive:Reports/2026/sales.xlsx", "excel", false, null, false, null, true, token, "me/drive", base);
            assertEquals("start", xl.getCell(ctx, "A1", "Sales", "excel").get("value"));
            xl.setCell(ctx, "B1", "edited by the agent", "Sales", "excel");
            xl.save(ctx, null, "excel");
            assertTrue(auth.stream().allMatch("Bearer graph-token"::equals), auth.toString());
            try (XSSFWorkbook back = new XSSFWorkbook(new ByteArrayInputStream(drive.get("Reports/2026/sales.xlsx")))) {
                assertEquals("edited by the agent", back.getSheet("Sales").getRow(0).getCell(1).getStringCellValue(), "the drive holds the saved workbook");
            }

            // a new workbook on the drive is made on its first save
            xl.open(ctx, "onedrive:Reports/new.xlsx", "fresh", true, null, false, null, true, token, "me/drive", base);
            xl.setCell(ctx, "A1", 42, null, "fresh");
            xl.close(ctx, "fresh", true);
            assertTrue(drive.containsKey("Reports/new.xlsx"));
        } finally { graph.stop(0); }
    }
}
