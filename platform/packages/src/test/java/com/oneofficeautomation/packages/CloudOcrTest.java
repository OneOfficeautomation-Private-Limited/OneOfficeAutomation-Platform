package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ConnectorsTest.Fake;
import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** G58: the OCR toolkit reads through a cloud engine (Azure Document Intelligence's read model) with that toolkit's session. */
class CloudOcrTest {
    @Test void readsAFileThroughAzure() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /documentintelligence/documentModels/prebuilt-read:analyze", c -> new Object[]{202, "",
                    Map.of("Operation-Location", f.url() + "/documentintelligence/documentModels/prebuilt-read/analyzeResults/r1?api-version=2024-11-30", "Retry-After", "0")})
             .on("GET /documentintelligence/documentModels/.*/analyzeResults/r1", c -> new Object[]{200,
                    JsonLite.write(Map.of("status", "succeeded", "analyzeResult", AzureAiPackageTest.invoiceResult())), Map.of("Content-Type", "application/json")});
            Path w = PackagesTest.tmp();
            Files.write(w.resolve("scan.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G'});
            ActionContext ctx = PackagesTestSupport.ctx(w, Map.of());
            new AzureAiPackage().connect(ctx, f.url() + "/", AzureAiPackageTest.KEY, "key", "azure_ai");
            Map<String, Object> r = new OcrPackage().readWithCloud(ctx, "azure", "scan.png", null, null, null, null, null, "en-US");
            assertEquals("INVOICE INV-100\nPens 12.50", r.get("text"));
            assertEquals("azure", r.get("engine"));
            assertTrue(f.last("POST", "/documentintelligence/documentModels/prebuilt-read:analyze").query().contains("locale=en-US"));
            assertEquals("OCR.BAD_ENGINE", assertThrows(ActionException.class, () -> new OcrPackage().readWithCloud(ctx, "abbyy", "scan.png", null, null, null, null, null, null)).code());
        }
    }
}
