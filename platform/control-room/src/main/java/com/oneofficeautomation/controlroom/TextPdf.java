package com.oneofficeautomation.controlroom;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A plain PDF of lines of text (a title, then lines; A4, Helvetica), for a dashboard e-mailed on a schedule. Written
 * here rather than with a PDF library: only text is needed. Long lines wrap; pages follow as needed; characters outside
 * Latin-1 become "?" (the standard fonts carry only those).
 */
final class TextPdf {
    private TextPdf() {}

    static final int WIDTH = 595, HEIGHT = 842, MARGIN = 50, SIZE = 10, LEADING = 14, TITLE = 15, WRAP = 100;

    static byte[] of(String title, List<String> lines) {
        List<String> wrapped = new ArrayList<>();
        for (String l : lines) {
            String s = l == null ? "" : l;
            if (s.isEmpty()) { wrapped.add(""); continue; }
            String pad = s.substring(0, s.length() - s.stripLeading().length()) + "  ";   // a wrapped line's continuation is indented
            if (pad.length() > WRAP / 2) pad = "  ";   // never so deep that a line cannot get shorter
            while (s.length() > WRAP) {
                int cut = s.lastIndexOf(' ', WRAP);
                if (cut <= pad.length()) cut = WRAP;   // no space to break at after the indent: break the word
                wrapped.add(s.substring(0, cut));
                s = pad + s.substring(cut).stripLeading();
            }
            wrapped.add(s);
        }
        int perPage = (HEIGHT - 2 * MARGIN - 2 * LEADING) / LEADING;
        List<List<String>> pages = new ArrayList<>();
        for (int i = 0; i < Math.max(1, wrapped.size()); i += perPage) pages.add(wrapped.subList(i, Math.min(wrapped.size(), i + perPage)));

        List<String> objects = new ArrayList<>();
        objects.add("<< /Type /Catalog /Pages 2 0 R >>");
        StringBuilder kids = new StringBuilder();
        for (int p = 0; p < pages.size(); p++) kids.append(4 + 2 * p).append(" 0 R ");
        objects.add("<< /Type /Pages /Kids [" + kids.toString().trim() + "] /Count " + pages.size() + " >>");
        objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>");
        for (int p = 0; p < pages.size(); p++) {
            StringBuilder c = new StringBuilder("BT\n");
            int y = HEIGHT - MARGIN;
            if (p == 0) { c.append("/F1 ").append(TITLE).append(" Tf\n").append(MARGIN).append(' ').append(y - TITLE).append(" Td\n(").append(esc(title)).append(") Tj\n"); y -= TITLE + LEADING; }
            else y -= LEADING;
            c.append("ET\nBT\n/F1 ").append(SIZE).append(" Tf\n").append(LEADING).append(" TL\n").append(MARGIN).append(' ').append(y - SIZE).append(" Td\n");
            for (String line : pages.get(p)) c.append('(').append(esc(line)).append(") Tj T*\n");
            c.append("ET\nBT\n/F1 8 Tf\n").append(WIDTH - MARGIN - 60).append(' ').append(MARGIN / 2).append(" Td\n(Page ").append(p + 1).append(" of ").append(pages.size()).append(") Tj\nET\n");
            byte[] stream = c.toString().getBytes(StandardCharsets.ISO_8859_1);
            objects.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + WIDTH + " " + HEIGHT + "] /Resources << /Font << /F1 3 0 R >> >> /Contents " + (5 + 2 * p) + " 0 R >>");
            objects.add("<< /Length " + stream.length + " >>\nstream\n" + c + "endstream");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, "%PDF-1.4\n%âãÏÓ\n");
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objects.size(); i++) { offsets.add(out.size()); write(out, (i + 1) + " 0 obj\n" + objects.get(i) + "\nendobj\n"); }
        int xref = out.size();
        StringBuilder x = new StringBuilder("xref\n0 " + (objects.size() + 1) + "\n0000000000 65535 f \n");
        for (int o : offsets) x.append(String.format("%010d 00000 n \n", o));
        x.append("trailer\n<< /Size ").append(objects.size() + 1).append(" /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        write(out, x.toString());
        return out.toByteArray();
    }

    /** A PDF string: backslash, brackets escaped; characters Helvetica cannot show become "?". */
    static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if (ch == '\\' || ch == '(' || ch == ')') b.append('\\').append(ch);
            else if (ch == '\t') b.append("    ");
            else if (ch < 32) continue;
            else if (ch > 255) b.append('?');
            else b.append(ch);
        }
        return b.toString();
    }

    private static void write(ByteArrayOutputStream out, String s) { out.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1)); }
}
