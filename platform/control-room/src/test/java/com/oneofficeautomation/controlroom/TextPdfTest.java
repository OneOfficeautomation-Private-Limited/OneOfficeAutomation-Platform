package com.oneofficeautomation.controlroom;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** The plain PDF a dashboard e-mail carries: valid structure, escaped text, long lines wrapped, pages as needed. */
class TextPdfTest {
    @Test void aPdfOfLines() {
        List<String> lines = new ArrayList<>(List.of("Runs: 5", "A (bracketed) back\\slash", "\u4e2d not in Helvetica", "x".repeat(250)));
        for (int i = 0; i < 120; i++) lines.add("Line " + i);
        String pdf = new String(TextPdf.of("Ops, Mon 2 Nov 2026", lines), StandardCharsets.ISO_8859_1);
        assertTrue(pdf.startsWith("%PDF-1.4"));
        assertTrue(pdf.trim().endsWith("%%EOF"));
        assertTrue(pdf.contains("(Ops, Mon 2 Nov 2026) Tj"));
        assertTrue(pdf.contains("(A \\(bracketed\\) back\\\\slash) Tj"), "brackets and backslashes escaped");
        assertTrue(pdf.contains("(? not in Helvetica) Tj"));
        assertTrue(pdf.contains("/Count 3"), "three pages");
        int xref = Integer.parseInt(pdf.substring(pdf.indexOf("startxref") + 10).trim().split("\\s+")[0]);
        assertTrue(pdf.startsWith("xref", xref), "startxref points at the table");
    }
}
