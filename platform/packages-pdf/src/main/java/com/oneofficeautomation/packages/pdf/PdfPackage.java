package com.oneofficeautomation.packages.pdf;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

/** PDF documents with Apache PDFBox: text, merge, split, forms, encryption and page images. */
@OoaPackage(id = "ooa.pdf", name = "PDF", version = "1.0.0",
        description = "Extract text, merge, split, read and fill forms, encrypt, decrypt and convert PDF pages to images.")
public final class PdfPackage {

    private static final java.util.concurrent.atomic.AtomicBoolean FONTS_STARTED = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Starts PDFBox's list of this computer's fonts in the background. PDFBox makes the list the first time it reads a font
     * that a PDF names without embedding it (Helvetica, Arial and the like), and keeps it in .pdfbox.cache in the user's
     * folder; on a computer where that never happened the first Extract text, Extract text from area or Convert to images
     * waited for all of it: from 1 to more than 5 minutes on the Windows machines of GitHub's CI (STEP.TIMEOUT after 300 s
     * on 2 Nov 2026). Hosts that run bots call this when they start (FirstPartyPackages.warmUp), so the first PDF step waits
     * for what is left at most. Once per process; never fails.
     */
    public static void warmUp() {
        if (!FONTS_STARTED.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            try { org.apache.pdfbox.pdmodel.font.FontMappers.instance().getFontBoxFont("Helvetica", null); }
            catch (RuntimeException | LinkageError ignored) { /* the first PDF step that needs the fonts makes the list then */ }
        }, "ooa-pdf-fonts");
        t.setDaemon(true);
        t.start();
    }

    private static PDDocument load(ActionContext ctx, String path, Object password) {
        Path p = ctx.resolvePath(path);
        if (!Files.isRegularFile(p)) throw new ActionException("FILE.NOT_FOUND", "PDF not found: " + p, "Check the path.");
        try {
            return password instanceof SecureString s ? Loader.loadPDF(p.toFile(), s.reveal()) : Loader.loadPDF(p.toFile());
        } catch (InvalidPasswordException e) {
            throw new ActionException("PDF.PASSWORD_REQUIRED", "The PDF is password-protected or the password is wrong", "Provide the password from the vault.");
        } catch (IOException e) { throw new ActionException("PDF.INVALID", "Cannot read PDF " + p + ": " + e.getMessage(), "Check it is a valid PDF file."); }
    }

    private static void save(ActionContext ctx, PDDocument doc, String target) { save(ctx, doc, target, true); }

    private static void save(ActionContext ctx, PDDocument doc, String target, boolean overwrite) {
        Path out = ctx.resolvePath(target);
        if (!overwrite && Files.exists(out))
            throw new ActionException("PDF.EXISTS", "A file already exists at " + out, "Set overwrite to true or choose another name.");
        try {
            if (out.getParent() != null) Files.createDirectories(out.getParent());
            doc.save(out.toFile());
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot save " + out + ": " + e.getMessage(), "Close the file if it is open.", true); }
    }

    /**
     * Parses a page list such as "1,3,5-7" (1-based, order kept, "-" ranges inclusive, an open end like "4-" runs to the
     * last page) into page numbers; every page when the text is empty.
     */
    static List<Integer> pages(String spec, int pageCount) {
        List<Integer> out = new ArrayList<>();
        if (spec == null || spec.isBlank()) { for (int i = 1; i <= pageCount; i++) out.add(i); return out; }
        for (String part : spec.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            if (!p.matches("\\d+(\\s*-\\s*\\d*)?"))
                throw new ActionException("PDF.BAD_PAGES", "'" + p + "' is not a page or page range", "Use page numbers and ranges such as 1,3,5-7.");
            String[] ab = p.split("-", -1);
            int a = Integer.parseInt(ab[0].trim());
            int b = ab.length == 1 ? a : ab[1].isBlank() ? pageCount : Integer.parseInt(ab[1].trim());
            if (a < 1 || b < a || b > pageCount)
                throw new ActionException("PDF.BAD_PAGES", "Pages '" + p + "' are outside 1-" + pageCount, "The document has " + pageCount + " pages.");
            for (int i = a; i <= b; i++) out.add(i);
        }
        if (out.isEmpty()) throw new ActionException("PDF.BAD_PAGES", "No pages selected", "Use page numbers and ranges such as 1,3,5-7.");
        return out;
    }

    public Map<String, Object> extractText(ActionContext ctx, String path, int from, Integer to, Object password) {
        return extractText(ctx, path, from, to, password, "structured", null, null, true);
    }

    @Action(id = "extract_text", name = "Extract text", category = "Text", idempotent = true, timeoutSeconds = 300, capabilities = "filesystem",
            description = "Extract the text of a PDF, of a page range or of chosen pages, as plain or layout-ordered text, optionally into a .txt file. Scanned PDFs (images) need OCR instead.",
            panel = "path, [The file has a password: password], mode, (Pages: All pages = / A page range = from_page, to_page / Listed pages = pages), [Save the text to a file: output_file, overwrite]")
    @Output(name = "text", type = OoaType.STRING, description = "Extracted text.")
    @Output(name = "page_count", type = OoaType.NUMBER, description = "Total pages in the document.")
    public Map<String, Object> extractText(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "PDF file.") String path,
            @Input(name = "from_page", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "First page (1-based).") int from,
            @Input(name = "to_page", type = OoaType.NUMBER, required = false, description = "Last page; the end when empty.") Integer to,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password for protected PDFs.") Object password,
            @Input(name = "mode", type = OoaType.STRING, required = false, defaultValue = "\"structured\"", enumValues = {"structured", "plain"},
                    description = "structured orders text by its position on the page, keeping columns and tables readable; plain keeps the order stored in the file, like copy and paste.") String mode,
            @Input(name = "pages", type = OoaType.STRING, required = false, example = "1,3,5-7",
                    description = "Exact pages to read, e.g. 1,3,5-7, in that order; overrides from_page and to_page.") String pages,
            @Input(name = "output_file", type = OoaType.FILE, required = false, description = "Also write the text to this .txt file (UTF-8).") String outputFile,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace output_file when it already exists; false stops with PDF.EXISTS.") boolean overwrite) {
        try (PDDocument d = load(ctx, path, password)) {
            PDFTextStripper s = new PDFTextStripper();
            s.setSortByPosition(!"plain".equalsIgnoreCase(mode));
            String text;
            if (pages != null && !pages.isBlank()) {
                StringBuilder sb = new StringBuilder();
                for (int pg : pages(pages, d.getNumberOfPages())) { s.setStartPage(pg); s.setEndPage(pg); sb.append(s.getText(d)); }
                text = sb.toString();
            } else {
                int count = d.getNumberOfPages();
                // a start page past the end used to return "" as if the page were empty
                if (from > count || from < 1 || (to != null && to < from))
                    throw new ActionException("PDF.BAD_PAGES", from > count ? "There is no page " + from + "; the document has " + count + " page" + (count == 1 ? "" : "s")
                            : "from_page and to_page must be 1 or more, with to_page not before from_page", "The document has " + count + " page" + (count == 1 ? "" : "s") + ".");
                s.setStartPage(from);
                s.setEndPage(to == null ? count : Math.min(to, count));
                text = s.getText(d);
            }
            if (outputFile != null && !outputFile.isBlank()) {
                Path out = ctx.resolvePath(outputFile);
                if (!overwrite && Files.exists(out)) throw new ActionException("PDF.EXISTS", "A file already exists at " + out, "Set overwrite to true or choose another name.");
                if (out.getParent() != null) Files.createDirectories(out.getParent());
                Files.writeString(out, text, java.nio.charset.StandardCharsets.UTF_8);
            }
            return Map.of("text", text, "page_count", BigDecimal.valueOf(d.getNumberOfPages()));
        } catch (IOException e) { throw new ActionException("PDF.TEXT_FAILED", e.getMessage()); }
    }

    @Action(id = "extract_area_text", name = "Extract text from area", category = "Text", idempotent = true, timeoutSeconds = 300, capabilities = "filesystem",
            description = "Read the text inside a rectangle of one page, e.g. an invoice number box that is not a form field.",
            panel = "path, [The file has a password: password], page, x, y, width, height")
    @Output(name = "text", type = OoaType.STRING, description = "Text found inside the rectangle, trimmed.")
    public Map<String, Object> extractAreaText(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "PDF file.") String path,
            @Input(name = "page", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "1-based page number.") int page,
            @Input(name = "x", type = OoaType.NUMBER, description = "Left edge of the area in points (72 per inch) from the left of the page.") double x,
            @Input(name = "y", type = OoaType.NUMBER, description = "Top edge of the area in points from the top of the page.") double y,
            @Input(name = "width", type = OoaType.NUMBER, description = "Width of the area in points.") double width,
            @Input(name = "height", type = OoaType.NUMBER, description = "Height of the area in points.") double height,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password for protected PDFs.") Object password) {
        if (width <= 0 || height <= 0) throw new ActionException("PDF.BAD_AREA", "Width and height must be greater than 0", "Measure the area in points from the top-left corner of the page.");
        try (PDDocument d = load(ctx, path, password)) {
            if (page < 1 || page > d.getNumberOfPages())
                throw new ActionException("PDF.BAD_PAGES", "There is no page " + page, "The document has " + d.getNumberOfPages() + " pages.");
            org.apache.pdfbox.text.PDFTextStripperByArea s = new org.apache.pdfbox.text.PDFTextStripperByArea();
            s.setSortByPosition(true);
            s.addRegion("area", new java.awt.geom.Rectangle2D.Double(x, y, width, height));
            s.extractRegions(d.getPage(page - 1));
            return Map.of("text", s.getTextForRegion("area").strip());
        } catch (IOException e) { throw new ActionException("PDF.TEXT_FAILED", e.getMessage()); }
    }

    @Action(id = "page_count", name = "Get page count", category = "Document", idempotent = true, capabilities = "filesystem", description = "Return the number of pages.",
            panel = "path, [The file has a password: password]")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of pages.")
    public Map<String, Object> pageCount(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "PDF file.") String path,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password for protected PDFs.") Object password) {
        try (PDDocument d = load(ctx, path, password)) { return Map.of("count", BigDecimal.valueOf(d.getNumberOfPages())); }
        catch (IOException e) { throw new ActionException("PDF.INVALID", e.getMessage()); }
    }

    public Map<String, Object> merge(ActionContext ctx, List<Object> files, String target) { return merge(ctx, files, target, true, null, null); }

    public Map<String, Object> merge(ActionContext ctx, List<Object> files, String target, boolean overwrite, List<Object> pages, List<Object> passwords) {
        return merge(ctx, files, target, overwrite, pages, passwords, null, "*.pdf", "name");
    }

    /** The PDFs of a folder (never the merge target itself), by name or by modification time. */
    private static List<Object> folderPdfs(ActionContext ctx, String folder, String pattern, String sortBy, String target) {
        java.nio.file.Path dir = ctx.resolvePath(folder);
        if (!Files.isDirectory(dir)) throw new ActionException("FILE.NOT_FOUND", "Folder not found: " + dir, "Check the folder path.");
        java.nio.file.PathMatcher m = java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + (pattern == null || pattern.isBlank() ? "*.pdf" : pattern));
        java.nio.file.Path out = ctx.resolvePath(target).toAbsolutePath().normalize();
        try (var s = Files.list(dir)) {
            List<java.nio.file.Path> found = new ArrayList<>(s.filter(Files::isRegularFile).filter(x -> m.matches(x.getFileName()))
                    .filter(x -> !x.toAbsolutePath().normalize().equals(out)).toList());
            Comparator<java.nio.file.Path> byName = Comparator.comparing(x -> x.getFileName().toString().toLowerCase(Locale.ROOT));
            found.sort("modified".equals(sortBy) ? Comparator.comparingLong((java.nio.file.Path x) -> x.toFile().lastModified()).thenComparing(byName) : byName);
            return new ArrayList<>(found.stream().map(java.nio.file.Path::toString).toList());
        } catch (IOException e) { throw new ActionException("PDF.MERGE_FAILED", "Cannot list " + dir + ": " + e.getMessage()); }
    }

    @Action(id = "merge", name = "Merge PDFs", category = "Document", timeoutSeconds = 600, capabilities = "filesystem",
            description = "Combine several PDFs (a list, or every PDF in a folder), or chosen pages of each, in order, into one file; protected sources can be opened with passwords.",
            panel = "(Files to merge: Listed files = files / Every PDF in a folder = folder, pattern, sort_by), target, overwrite | pages, passwords")
    @Output(name = "page_count", type = OoaType.NUMBER, description = "Pages in the merged file.")
    @Output(name = "files", type = OoaType.LIST, description = "The PDFs that were merged, in order.")
    public Map<String, Object> merge(ActionContext ctx,
            @Input(name = "files", type = OoaType.LIST, required = false, visibleWhen = "folder=", description = "PDF paths in the order to merge.") List<Object> files,
            @Input(name = "target", type = OoaType.FILE, description = "Merged PDF to create.") String target,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace the target when it already exists; false stops with PDF.EXISTS.") boolean overwrite,
            @Input(name = "pages", type = OoaType.LIST, required = false,
                    description = "Pages to take from each file, in the same order as files, e.g. [\"1-3\", \"\", \"5,2\"]; an empty entry takes every page.") List<Object> pages,
            @Input(name = "passwords", type = OoaType.LIST, required = false,
                    description = "Passwords (Credentials) for protected files, in the same order as files; empty entries for unprotected ones.") List<Object> passwords,
            @Input(name = "folder", type = OoaType.FILE, required = false, visibleWhen = "files=",
                    description = "Merge every PDF in this folder instead of a list, sorted by name (or by sort_by).") String folder,
            @Input(name = "pattern", type = OoaType.STRING, required = false, defaultValue = "\"*.pdf\"", visibleWhen = "folder!=", description = "Which files of the folder, e.g. invoice-*.pdf.") String pattern,
            @Input(name = "sort_by", type = OoaType.STRING, required = false, defaultValue = "\"name\"", enumValues = {"name", "modified"}, visibleWhen = "folder!=",
                    description = "Order of the folder's files: by name or by last change (oldest first).") String sortBy) {
        if ((files == null || files.isEmpty()) && folder != null && !folder.isBlank()) files = folderPdfs(ctx, folder, pattern, sortBy, target);
        if (files == null || files.size() < 2) throw new ActionException("PDF.NEED_FILES", "Give at least two PDFs to merge", "Fill in files, or a folder with two or more PDFs.");
        if (pages != null && !pages.isEmpty() && pages.size() != files.size())
            throw new ActionException("PDF.BAD_PAGES", "pages has " + pages.size() + " entries for " + files.size() + " files", "Give one entry per file, empty for all pages.");
        if (!overwrite && Files.exists(ctx.resolvePath(target)))
            throw new ActionException("PDF.EXISTS", "A file already exists at " + ctx.resolvePath(target), "Set overwrite to true or choose another name.");
        List<PDDocument> sources = new ArrayList<>();
        try (PDDocument out = new PDDocument()) {
            for (int i = 0; i < files.size(); i++) {
                Object pw = passwords != null && i < passwords.size() ? passwords.get(i) : null;
                PDDocument src = load(ctx, Values.text(files.get(i)), pw instanceof String str && str.isEmpty() ? null : pw);
                sources.add(src);
                String spec = pages == null || pages.isEmpty() || pages.get(i) == null ? null : Values.text(pages.get(i));
                for (int pg : pages(spec, src.getNumberOfPages())) out.importPage(src.getPage(pg - 1));
            }
            save(ctx, out, target);
            return Map.of("page_count", BigDecimal.valueOf(out.getNumberOfPages()), "files", new ArrayList<>(files));
        } catch (IOException e) { throw new ActionException("PDF.MERGE_FAILED", e.getMessage());
        } finally { for (PDDocument d : sources) try { d.close(); } catch (IOException ignored) { } }
    }

    public Map<String, Object> split(ActionContext ctx, String path, String folder, int n) { return split(ctx, path, folder, n, "pages_per_file", null, null, true, null); }

    public Map<String, Object> split(ActionContext ctx, String path, String folder, int n, String mode, String pages, String prefix, boolean overwrite, Object password) {
        return split(ctx, path, folder, n, mode, pages, prefix, overwrite, password, 1);
    }

    @Action(id = "split", name = "Split PDF", category = "Document", timeoutSeconds = 600, capabilities = "filesystem",
            description = "Split a PDF into files of N pages each, into one file per page range, into one file holding chosen pages, at blank separator pages, or at its bookmarks.",
            panel = "path, [The file has a password: password], mode, pages_per_file, pages, bookmark_level, target_folder, file_prefix, overwrite")
    @Output(name = "files", type = OoaType.LIST, description = "Created files.")
    public Map<String, Object> split(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "PDF file.") String path,
            @Input(name = "target_folder", type = OoaType.FILE, description = "Folder for the parts.") String folder,
            @Input(name = "pages_per_file", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "Pages in each part, for mode pages_per_file.") int n,
            @Input(name = "mode", type = OoaType.STRING, required = false, defaultValue = "\"pages_per_file\"", enumValues = {"pages_per_file", "ranges", "selected_pages", "blank_page", "bookmarks"},
                    description = "pages_per_file cuts every N pages; ranges makes one file per comma-separated range in pages; selected_pages puts all pages listed in pages into one file; "
                            + "blank_page starts a new file after each blank page (blank pages are left out); bookmarks starts a new file at each bookmark down to bookmark_level.") String mode,
            @Input(name = "pages", type = OoaType.STRING, required = false, example = "1-3,4,5-9", description = "Page ranges for modes ranges and selected_pages.") String pages,
            @Input(name = "file_prefix", type = OoaType.STRING, required = false, description = "Start of each part's file name, followed by _part1, _part2 and so on; the source file name when empty.") String prefix,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace parts that already exist; false stops with PDF.EXISTS.") boolean overwrite,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password for protected PDFs.") Object password,
            @Input(name = "bookmark_level", type = OoaType.NUMBER, required = false, defaultValue = "1",
                    description = "For mode bookmarks: the deepest bookmark level that starts a new file (1 = top-level bookmarks only, 2 = also their sub-bookmarks).") int bookmarkLevel) {
        List<Object> created = new ArrayList<>();
        String m = mode == null ? "pages_per_file" : mode.toLowerCase(Locale.ROOT);
        if ("bookmarks".equals(m) && bookmarkLevel < 1)
            throw new ActionException("PDF.BAD_BOOKMARK_LEVEL", "bookmark_level must be 1 or more, not " + bookmarkLevel, "Use 1 for top-level bookmarks, 2 to include their sub-bookmarks.");
        try (PDDocument d = load(ctx, path, password)) {
            String base = prefix != null && !prefix.isBlank() ? prefix : Path.of(path).getFileName().toString().replaceFirst("(?i)\\.pdf$", "");
            List<List<Integer>> groups = new ArrayList<>();
            switch (m) {
                case "pages_per_file" -> {
                    int k = Math.max(1, n);
                    for (int a = 1; a <= d.getNumberOfPages(); a += k) {
                        List<Integer> g = new ArrayList<>();
                        for (int pg = a; pg < a + k && pg <= d.getNumberOfPages(); pg++) g.add(pg);
                        groups.add(g);
                    }
                }
                case "ranges" -> {
                    if (pages == null || pages.isBlank()) throw new ActionException("PDF.BAD_PAGES", "Mode ranges needs pages, e.g. 1-3,4-6", "Set pages.");
                    for (String r : pages.split(",")) if (!r.isBlank()) groups.add(pages(r, d.getNumberOfPages()));
                }
                case "selected_pages" -> {
                    if (pages == null || pages.isBlank()) throw new ActionException("PDF.BAD_PAGES", "Mode selected_pages needs pages, e.g. 1,3,5-7", "Set pages.");
                    groups.add(pages(pages, d.getNumberOfPages()));
                }
                case "blank_page" -> {
                    List<Integer> g = new ArrayList<>();
                    for (int pg = 1; pg <= d.getNumberOfPages(); pg++) {
                        ctx.checkCancelled();
                        if (isBlankPage(d, pg)) { if (!g.isEmpty()) groups.add(g); g = new ArrayList<>(); }
                        else g.add(pg);
                    }
                    if (!g.isEmpty()) groups.add(g);
                    if (groups.isEmpty()) throw new ActionException("PDF.NO_PAGES", "Every page of the PDF is blank", "Check the file, or use another split mode.");
                }
                case "bookmarks" -> {
                    TreeSet<Integer> starts = new TreeSet<>();
                    bookmarkStarts(d, d.getDocumentCatalog().getDocumentOutline(), 1, bookmarkLevel, starts);
                    if (starts.isEmpty())
                        throw new ActionException("PDF.NO_BOOKMARKS", "The PDF has no bookmarks pointing to pages", "Use another split mode, or add bookmarks to the PDF.");
                    starts.add(1);
                    List<Integer> cuts = new ArrayList<>(starts);
                    for (int i = 0; i < cuts.size(); i++) {
                        int end = i + 1 < cuts.size() ? cuts.get(i + 1) - 1 : d.getNumberOfPages();
                        List<Integer> g = new ArrayList<>();
                        for (int pg = cuts.get(i); pg <= end; pg++) g.add(pg);
                        if (!g.isEmpty()) groups.add(g);
                    }
                }
                default -> throw new ActionException("PDF.BAD_MODE", "Unknown split mode '" + mode + "'", "Use pages_per_file, ranges, selected_pages, blank_page or bookmarks.");
            }
            for (int i = 0; i < groups.size(); i++) {
                ctx.checkCancelled();
                try (PDDocument part = new PDDocument()) {
                    for (int pg : groups.get(i)) part.importPage(d.getPage(pg - 1));
                    String rel = Path.of(folder, base + "_part" + (i + 1) + ".pdf").toString();
                    save(ctx, part, rel, overwrite);
                    created.add(ctx.resolvePath(rel).toString());
                }
            }
        } catch (IOException e) { throw new ActionException("PDF.SPLIT_FAILED", e.getMessage()); }
        return Map.of("files", created);
    }

    /** A page is blank when it has no text and, drawn at low resolution, almost no dark pixels (allows scanner specks). */
    static boolean isBlankPage(PDDocument d, int page) throws IOException {
        PDFTextStripper s = new PDFTextStripper();
        s.setStartPage(page); s.setEndPage(page);
        if (!s.getText(d).isBlank()) return false;
        java.awt.image.BufferedImage img = new PDFRenderer(d).renderImageWithDPI(page - 1, 36, org.apache.pdfbox.rendering.ImageType.GRAY);
        long dark = 0, total = (long) img.getWidth() * img.getHeight();
        var raster = img.getRaster();
        for (int y = 0; y < img.getHeight(); y++)
            for (int x = 0; x < img.getWidth(); x++)
                if (raster.getSample(x, y, 0) < 200) dark++;
        return total == 0 || dark * 1000 <= total * 2;
    }

    /** Adds the 1-based start page of every bookmark down to maxLevel (walking the outline tree). */
    private static void bookmarkStarts(PDDocument d, org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode node,
                                       int level, int maxLevel, Set<Integer> starts) throws IOException {
        if (node == null || level > maxLevel) return;
        for (var item : node.children()) {
            PDPage p = item.findDestinationPage(d);
            if (p != null) { int i = d.getPages().indexOf(p); if (i >= 0) starts.add(i + 1); }
            bookmarkStarts(d, item, level + 1, maxLevel, starts);
        }
    }

    @Action(id = "read_form", name = "Read form fields", category = "Forms", idempotent = true, capabilities = "filesystem", description = "Read the names and values of all form fields, or only of the fields named.",
            panel = "path, [The form has a password: password], [Read only some fields: field_names]")
    @Output(name = "fields", type = OoaType.DICTIONARY, description = "Field name to value.")
    public Map<String, Object> readForm(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "PDF form.") String path,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password for protected PDFs.") Object password,
            @Input(name = "field_names", type = OoaType.LIST, required = false, example = "[\"customer\", \"total\"]",
                    description = "Read only these fields, in this order; every field when empty. A name the form does not have stops the step.") List<Object> fieldNames) {
        List<String> wanted = new ArrayList<>();
        if (fieldNames != null) for (Object o : fieldNames) if (o != null && !String.valueOf(o).isBlank()) wanted.add(String.valueOf(o).strip());
        try (PDDocument d = load(ctx, path, password)) {
            PDAcroForm form = d.getDocumentCatalog().getAcroForm();
            Map<String, Object> out = new LinkedHashMap<>();
            if (wanted.isEmpty()) {
                if (form != null) for (PDField f : form.getFieldTree()) if (f.getFieldType() != null) out.put(f.getFullyQualifiedName(), f.getValueAsString());
                return Map.of("fields", out);
            }
            if (form == null) throw new ActionException("PDF.NO_FORM", "This PDF has no form fields");
            List<String> missing = new ArrayList<>();
            for (String name : wanted) {
                PDField f = form.getField(name);
                if (f == null) missing.add(name); else out.put(name, f.getValueAsString());
            }
            if (!missing.isEmpty()) throw new ActionException("PDF.NO_FIELD", "Fields not found: " + missing, "Use Read form fields without field names to see the exact names.");
            return Map.of("fields", out);
        } catch (IOException e) { throw new ActionException("PDF.INVALID", e.getMessage()); }
    }

    public Map<String, Object> readForm(ActionContext ctx, String path, Object password) {
        return readForm(ctx, path, password, null);
    }

    public Map<String, Object> fillForm(ActionContext ctx, String path, Map<String, Object> values, String target, boolean flatten) {
        return fillForm(ctx, path, values, target, flatten, null);
    }

    @Action(id = "fill_form", name = "Fill form fields", category = "Forms", capabilities = "filesystem",
            description = "Fill form fields by name and save a new PDF; optionally flatten so values can no longer be edited.",
            panel = "path, [The form has a password: password], values, target, flatten")
    @Output(name = "filled", type = OoaType.NUMBER, description = "Number of fields filled.")
    public Map<String, Object> fillForm(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "PDF form.") String path,
            @Input(name = "values", type = OoaType.DICTIONARY, description = "Field name to value.") Map<String, Object> values,
            @Input(name = "target", type = OoaType.FILE, description = "Filled PDF to create.") String target,
            @Input(name = "flatten", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Make the form read-only.") boolean flatten,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password for protected PDFs; the filled copy is saved without protection.") Object password) {
        try (PDDocument d = load(ctx, path, password)) {
            if (d.isEncrypted()) d.setAllSecurityToBeRemoved(true);
            PDAcroForm form = d.getDocumentCatalog().getAcroForm();
            if (form == null) throw new ActionException("PDF.NO_FORM", "This PDF has no form fields");
            int n = 0;
            List<String> missing = new ArrayList<>();
            for (var e : values.entrySet()) {
                PDField f = form.getField(e.getKey());
                if (f == null) { missing.add(e.getKey()); continue; }
                setField(f, e.getKey(), e.getValue());
                n++;
            }
            if (!missing.isEmpty()) throw new ActionException("PDF.NO_FIELD", "Fields not found: " + missing, "Use Read form fields to see the exact field names.");
            if (flatten) form.flatten();
            save(ctx, d, target);
            return Map.of("filled", BigDecimal.valueOf(n));
        } catch (IOException e) { throw new ActionException("PDF.FORM_FAILED", e.getMessage()); }
    }

    /**
     * A check box takes true/false (or yes/no, on/off, 1/0, x) and is set to its own on-value or Off: {"agree": true} used
     * to crash with IllegalArgumentException. A wrong choice for a check box, radio group or list is PDF.BAD_VALUE with the
     * allowed values.
     */
    static void setField(PDField f, String name, Object value) throws IOException {
        String text = value == null ? "" : Values.text(Values.toJsonable(value));
        try {
            if (f instanceof org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox cb) {
                String t = text.strip().toLowerCase(Locale.ROOT);
                Set<String> on = cb.getOnValues();
                if (value instanceof Boolean b ? b : List.of("true", "yes", "y", "on", "1", "x", "checked").contains(t)) cb.check();
                else if (value instanceof Boolean || List.of("false", "no", "n", "off", "0", "", "unchecked").contains(t)) cb.unCheck();
                else if (on.contains(text.strip())) cb.setValue(text.strip());
                else throw new ActionException("PDF.BAD_VALUE", "Check box '" + name + "' cannot be set to '" + text + "'",
                            "Use true or false" + (on.isEmpty() ? "." : ", or its on-value " + on + "."));
                return;
            }
            f.setValue(text);
        } catch (IllegalArgumentException e) {
            List<String> options = new ArrayList<>();
            if (f instanceof org.apache.pdfbox.pdmodel.interactive.form.PDButton btn) options.addAll(btn.getOnValues());
            if (f instanceof org.apache.pdfbox.pdmodel.interactive.form.PDChoice ch) options.addAll(ch.getOptionsExportValues().isEmpty() ? ch.getOptions() : ch.getOptionsExportValues());
            throw new ActionException("PDF.BAD_VALUE", "Field '" + name + "' cannot be set to '" + text + "'" + (options.isEmpty() ? ": " + e.getMessage() : ""),
                    options.isEmpty() ? "Use Read form fields to see what the field accepts." : "Allowed values: " + options + ".");
        }
    }

    public Map<String, Object> encrypt(ActionContext ctx, String path, String target, SecureString user, SecureString owner, boolean print) {
        return encrypt(ctx, path, target, user, owner, print, true, true, true, true, true, true, 256, true);
    }

    @Action(id = "encrypt", name = "Encrypt PDF", category = "Security", capabilities = "filesystem",
            description = "Protect a PDF with an open password and an owner password, choosing the AES key length and what readers may do (print, copy, edit, annotate, fill forms, assemble).",
            panel = "path, user_password, owner_password, allow_printing, allow_modify, allow_copy, allow_fill_forms, allow_assembly, allow_annotations, allow_accessibility, key_length, target, overwrite")
    public Map<String, Object> encrypt(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "PDF file.") String path,
            @Input(name = "target", type = OoaType.FILE, description = "Encrypted PDF to create.") String target,
            @Input(name = "user_password", type = OoaType.CREDENTIAL, description = "Password needed to open the PDF.") SecureString user,
            @Input(name = "owner_password", type = OoaType.CREDENTIAL, description = "Password needed to change permissions.") SecureString owner,
            @Input(name = "allow_printing", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Allow printing.") boolean print,
            @Input(name = "allow_copy", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Allow copying text and images.") boolean copy,
            @Input(name = "allow_modify", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Allow changing the content.") boolean modify,
            @Input(name = "allow_annotations", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Allow adding comments and annotations.") boolean annotate,
            @Input(name = "allow_fill_forms", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Allow filling in form fields.") boolean fillForms,
            @Input(name = "allow_assembly", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Allow inserting, rotating and deleting pages.") boolean assemble,
            @Input(name = "allow_accessibility", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Allow screen readers to extract the text.") boolean accessibility,
            @Input(name = "key_length", type = OoaType.NUMBER, required = false, defaultValue = "256", enumValues = {"128", "256"},
                    description = "AES key length in bits: 256 (recommended) or 128 for older readers.") int keyLength,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace the target when it already exists; false stops with PDF.EXISTS.") boolean overwrite) {
        if (keyLength != 128 && keyLength != 256) throw new ActionException("PDF.BAD_KEY_LENGTH", "Key length must be 128 or 256, not " + keyLength, "Use 256, or 128 for older readers.");
        try (PDDocument d = load(ctx, path, null)) {
            AccessPermission ap = new AccessPermission();
            ap.setCanPrint(print);
            ap.setCanPrintFaithful(print);
            ap.setCanExtractContent(copy);
            ap.setCanModify(modify);
            ap.setCanModifyAnnotations(annotate);
            ap.setCanFillInForm(fillForms);
            ap.setCanAssembleDocument(assemble);
            ap.setCanExtractForAccessibility(accessibility);
            StandardProtectionPolicy spp = new StandardProtectionPolicy(owner.reveal(), user.reveal(), ap);
            spp.setEncryptionKeyLength(keyLength);
            spp.setPreferAES(true);
            d.protect(spp);
            save(ctx, d, target, overwrite);
        } catch (IOException e) { throw new ActionException("PDF.ENCRYPT_FAILED", e.getMessage()); }
        return Map.of();
    }

    public Map<String, Object> decrypt(ActionContext ctx, String path, SecureString password, String target) { return decrypt(ctx, path, password, target, true); }

    @Action(id = "decrypt", name = "Decrypt PDF", category = "Security", capabilities = "filesystem", description = "Remove password protection (needs the open or owner password).",
            panel = "path, password, target, overwrite")
    public Map<String, Object> decrypt(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Protected PDF.") String path,
            @Input(name = "password", type = OoaType.CREDENTIAL, description = "Password from the vault.") SecureString password,
            @Input(name = "target", type = OoaType.FILE, description = "Unprotected PDF to create.") String target,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace the target when it already exists; false stops with PDF.EXISTS.") boolean overwrite) {
        try (PDDocument d = load(ctx, path, password)) {
            d.setAllSecurityToBeRemoved(true);
            save(ctx, d, target, overwrite);
        } catch (IOException e) { throw new ActionException("PDF.DECRYPT_FAILED", e.getMessage()); }
        return Map.of();
    }

    /** Writes an image in a format ImageIO knows; JPEG and BMP get an RGB copy because they have no alpha channel. */
    private static void writeImage(java.awt.image.BufferedImage img, String format, File f) throws IOException { writeImage(img, format, f, null, null); }

    /** Writes an image with the JPEG quality and TIFF compression chosen on the step (see ImageFiles). */
    private static void writeImage(java.awt.image.BufferedImage img, String format, File f, Integer jpegQuality, String tiffCompression) throws IOException {
        com.oneofficeautomation.packages.ImageFiles.write(img, format, f, jpegQuality, tiffCompression);
    }

    private static String imageFormat(String format) {
        String f = format == null || format.isBlank() ? "png" : format.toLowerCase(Locale.ROOT);
        if (f.equals("jpeg")) f = "jpg";
        if (f.equals("tif")) f = "tiff";
        if (!List.of("png", "jpg", "bmp", "gif", "tiff").contains(f))
            throw new ActionException("PDF.BAD_FORMAT", "Unsupported image format '" + format + "'", "Use png, jpg, bmp, gif or tiff.");
        return f;
    }

    public Map<String, Object> toImages(ActionContext ctx, String path, String folder, int dpi) { return toImages(ctx, path, folder, dpi, "png", "color", null, null, null); }

    public Map<String, Object> toImages(ActionContext ctx, String path, String folder, int dpi, String format, String colour, String pages, String prefix, Object password) {
        return toImages(ctx, path, folder, dpi, format, colour, pages, prefix, password, null, null);
    }

    @Action(id = "to_images", name = "Convert to images", category = "Document", timeoutSeconds = 600, capabilities = "filesystem",
            description = "Save each page, or chosen pages, as an image (PNG, JPEG, BMP, GIF or TIFF) in colour, grey or black and white, for OCR or previews.",
            panel = "path, [The file has a password: password], [Only some pages: pages], format, target_folder, dpi, color | jpeg_quality, tiff_compression, file_prefix")
    @Output(name = "files", type = OoaType.LIST, description = "Created images.")
    public Map<String, Object> toImages(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "PDF file.") String path,
            @Input(name = "target_folder", type = OoaType.FILE, description = "Folder for the images.") String folder,
            @Input(name = "dpi", type = OoaType.NUMBER, required = false, defaultValue = "150", description = "Resolution; 300 for OCR.") int dpi,
            @Input(name = "format", type = OoaType.STRING, required = false, defaultValue = "\"png\"", enumValues = {"png", "jpg", "bmp", "gif", "tiff"}, description = "Image file format.") String format,
            @Input(name = "color", type = OoaType.STRING, required = false, defaultValue = "\"color\"", enumValues = {"color", "gray", "black_white"},
                    description = "color keeps the colours; gray gives greyscale; black_white gives 1-bit images, often best for OCR.") String colour,
            @Input(name = "pages", type = OoaType.STRING, required = false, example = "1,3,5-7", description = "Pages to convert, e.g. 1,3,5-7; every page when empty.") String pages,
            @Input(name = "file_prefix", type = OoaType.STRING, required = false, description = "Start of each image's file name, followed by _page1 and so on; the PDF's name when empty.") String prefix,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password for protected PDFs.") Object password,
            @Input(name = "jpeg_quality", type = OoaType.NUMBER, required = false, defaultValue = "90", min = 1, max = 100, visibleWhen = "format=jpg|tiff",
                    description = "JPEG quality from 1 (smallest file) to 100 (best picture); also used for TIFF with jpeg compression.") Integer jpegQuality,
            @Input(name = "tiff_compression", type = OoaType.STRING, required = false, defaultValue = "\"lzw\"", enumValues = {"lzw", "deflate", "packbits", "jpeg", "ccitt_g4", "none"}, visibleWhen = "format=tiff",
                    description = "TIFF compression: lzw and deflate are lossless; ccitt_g4 gives the smallest black-and-white files (pages are made black and white); jpeg suits photos.") String tiffCompression) {
        List<Object> out = new ArrayList<>();
        String fmt = imageFormat(format);
        org.apache.pdfbox.rendering.ImageType type = switch (colour == null ? "color" : colour.toLowerCase(Locale.ROOT)) {
            case "gray", "grey" -> org.apache.pdfbox.rendering.ImageType.GRAY;
            case "black_white" -> org.apache.pdfbox.rendering.ImageType.BINARY;
            case "color", "colour" -> org.apache.pdfbox.rendering.ImageType.RGB;
            default -> throw new ActionException("PDF.BAD_COLOR", "Colour must be color, gray or black_white", "Use color, gray or black_white.");
        };
        try (PDDocument d = load(ctx, path, password)) {
            PDFRenderer r = new PDFRenderer(d);
            Path dir = ctx.resolvePath(folder);
            Files.createDirectories(dir);
            String base = prefix != null && !prefix.isBlank() ? prefix : Path.of(path).getFileName().toString().replaceFirst("(?i)\\.pdf$", "");
            for (int pg : pages(pages, d.getNumberOfPages())) {
                ctx.checkCancelled();
                File f = dir.resolve(base + "_page" + pg + "." + fmt).toFile();
                writeImage(r.renderImageWithDPI(pg - 1, Math.max(36, Math.min(600, dpi)), type), fmt, f, fmt.equals("jpg") || fmt.equals("tiff") ? jpegQuality : null, tiffCompression);
                out.add(f.getPath());
            }
        } catch (IOException e) { throw new ActionException("PDF.RENDER_FAILED", e.getMessage()); }
        return Map.of("files", out);
    }

    @Action(id = "render_page", name = "Get page as picture", category = "Document", idempotent = true, timeoutSeconds = 120, capabilities = "filesystem",
            description = "Render one page as a PNG picture, returned as data (data: URI) with the page size in points; used by One AgentStudio to draw an area to read, and to show a page in forms or emails.",
            panel = "path, page, [The file has a password: password] | dpi")
    @Output(name = "image", type = OoaType.STRING, description = "The page as a data:image/png;base64 URI.")
    @Output(name = "width_points", type = OoaType.NUMBER, description = "Page width in points (72 per inch), as Extract text from area measures it.")
    @Output(name = "height_points", type = OoaType.NUMBER, description = "Page height in points.")
    @Output(name = "page_count", type = OoaType.NUMBER, description = "Number of pages in the file.")
    public Map<String, Object> renderPage(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "PDF file.") String path,
            @Input(name = "page", type = OoaType.NUMBER, required = false, defaultValue = "1", min = 1, description = "1-based page number.") int page,
            @Input(name = "dpi", type = OoaType.NUMBER, required = false, defaultValue = "96", min = 36, max = 300, description = "Resolution of the picture.") int dpi,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password for protected PDFs.") Object password) {
        try (PDDocument d = load(ctx, path, password)) {
            if (page < 1 || page > d.getNumberOfPages())
                throw new ActionException("PDF.NO_PAGE", "There is no page " + page + " (the file has " + d.getNumberOfPages() + ")", "Use a page from 1 to " + d.getNumberOfPages() + ".");
            PDPage p = d.getPage(page - 1);
            org.apache.pdfbox.pdmodel.common.PDRectangle box = p.getCropBox();
            boolean turned = p.getRotation() % 180 != 0;
            java.awt.image.BufferedImage img = new PDFRenderer(d).renderImageWithDPI(page - 1, Math.max(36, Math.min(300, dpi)), org.apache.pdfbox.rendering.ImageType.RGB);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return Map.of("image", "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray()),
                    "width_points", BigDecimal.valueOf(turned ? box.getHeight() : box.getWidth()).setScale(2, java.math.RoundingMode.HALF_UP),
                    "height_points", BigDecimal.valueOf(turned ? box.getWidth() : box.getHeight()).setScale(2, java.math.RoundingMode.HALF_UP),
                    "page_count", BigDecimal.valueOf(d.getNumberOfPages()));
        } catch (IOException e) { throw new ActionException("PDF.RENDER_FAILED", e.getMessage()); }
    }

    @Action(id = "read_text_ocr", name = "Read text with OCR", category = "Document", idempotent = true, timeoutSeconds = 1800, capabilities = "filesystem",
            description = "Read the text of a scanned PDF (pages that are pictures, with no text layer) with Tesseract OCR, from every page or chosen pages, optionally keeping only the text between two markers.",
            panel = "path, [The file has a password: password], [Only some pages: pages], language | [Keep only part of the text: after_text, before_text], trim, dpi, page_mode")
    @Output(name = "text", type = OoaType.STRING, description = "Recognised text of the pages, in page order.")
    @Output(name = "pages", type = OoaType.LIST, description = "Recognised text of each page read, in page order.")
    public Map<String, Object> readTextOcr(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "PDF file.") String path,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password for protected PDFs.") Object password,
            @Input(name = "pages", type = OoaType.STRING, required = false, example = "1,3,5-7", description = "Pages to read, e.g. 1,3,5-7; every page when empty.") String pages,
            @Input(name = "language", type = OoaType.STRING, required = false, defaultValue = "\"eng\"", description = "Tesseract language(s), e.g. eng, deu or eng+fra.") String lang,
            @Input(name = "page_mode", type = OoaType.NUMBER, required = false, defaultValue = "3", min = 0, max = 13, description = "Page layout mode: 3 automatic, 6 single block, 7 single line, 11 sparse text.") int psm,
            @Input(name = "dpi", type = OoaType.NUMBER, required = false, defaultValue = "300", min = 72, max = 600, description = "Resolution the pages are rendered at for OCR; 300 suits most scans.") int dpi,
            @Input(name = "after_text", type = OoaType.STRING, required = false, description = "Keep only the text after the first occurrence of this marker, e.g. Invoice number:.") String after,
            @Input(name = "before_text", type = OoaType.STRING, required = false, description = "Keep only the text before this marker (searched after the after_text marker).") String before,
            @Input(name = "trim", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Remove spaces and line breaks at the start and end of the result.") boolean trim) {
        List<Object> each = new ArrayList<>();
        StringBuilder all = new StringBuilder();
        try (PDDocument d = load(ctx, path, password)) {
            PDFRenderer r = new PDFRenderer(d);
            for (int pg : pages(pages, d.getNumberOfPages())) {
                ctx.checkCancelled();
                String t = com.oneofficeautomation.packages.OcrPackage.readText(ctx, r.renderImageWithDPI(pg - 1, Math.max(72, Math.min(600, dpi)), org.apache.pdfbox.rendering.ImageType.GRAY), lang, psm, null, null, false);
                each.add(t);
                if (all.length() > 0) all.append('\n');
                all.append(t);
            }
        } catch (IOException e) { throw new ActionException("PDF.RENDER_FAILED", e.getMessage()); }
        // the markers may sit on different pages, so they apply to the whole text
        return Map.of("text", com.oneofficeautomation.packages.OcrPackage.filterText(all.toString(), after, before, trim), "pages", each);
    }

    public Map<String, Object> extractImages(ActionContext ctx, String path, String folder, int minSize, Object password) {
        return extractImages(ctx, path, folder, minSize, password, null, "png", null, "color");
    }

    public Map<String, Object> extractImages(ActionContext ctx, String path, String folder, int minSize, Object password,
                                             String pages, String format, String prefix, String colour) {
        return extractImages(ctx, path, folder, minSize, password, pages, format, prefix, colour, 0);
    }

    public Map<String, Object> extractImages(ActionContext ctx, String path, String folder, int minSize, Object password,
                                             String pages, String format, String prefix, String colour, int dpi) {
        return extractImages(ctx, path, folder, minSize, password, pages, format, prefix, colour, dpi, null, null);
    }

    @Action(id = "extract_images", name = "Extract images", category = "Document", timeoutSeconds = 600, capabilities = "filesystem",
            description = "Save the pictures embedded in a PDF (photos, logos, scanned pages), from every page or chosen pages, as image files in a folder.",
            panel = "path, [The file has a password: password], [Only some pages: pages], format, target_folder, file_prefix, color, [Resize to a resolution: dpi] | jpeg_quality, tiff_compression, min_size")
    @Output(name = "files", type = OoaType.LIST, description = "Created image files.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of images saved.")
    public Map<String, Object> extractImages(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "PDF file.") String path,
            @Input(name = "target_folder", type = OoaType.FILE, description = "Folder for the images.") String folder,
            @Input(name = "min_size", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "Skip images narrower or lower than this many pixels (e.g. 32 to skip icons).") int minSize,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password for protected PDFs.") Object password,
            @Input(name = "pages", type = OoaType.STRING, required = false, example = "1,3,5-7", description = "Only pages listed here, e.g. 1,3,5-7; every page when empty.") String pages,
            @Input(name = "format", type = OoaType.STRING, required = false, defaultValue = "\"png\"", enumValues = {"png", "jpg", "bmp", "gif", "tiff"}, description = "Image file format.") String format,
            @Input(name = "file_prefix", type = OoaType.STRING, required = false, description = "Start of each image's file name, followed by _page1_img1 and so on; the PDF's name when empty.") String prefix,
            @Input(name = "color", type = OoaType.STRING, required = false, defaultValue = "\"color\"", enumValues = {"color", "gray", "black_white"},
                    description = "Colour depth: color keeps the colours (24-bit); gray saves 8-bit greyscale copies; black_white saves 1-bit images.") String colour,
            @Input(name = "dpi", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, max = 1200,
                    description = "Resize each image to this many dots per inch at the size it is shown on the page (e.g. a picture shown 2 inches wide becomes 300 pixels wide at 150 dpi); 0 keeps the pixels stored in the PDF. An image the page does not draw keeps its stored size.") int dpi,
            @Input(name = "jpeg_quality", type = OoaType.NUMBER, required = false, defaultValue = "90", min = 1, max = 100, visibleWhen = "format=jpg|tiff",
                    description = "JPEG quality from 1 (smallest file) to 100 (best picture); also used for TIFF with jpeg compression.") Integer jpegQuality,
            @Input(name = "tiff_compression", type = OoaType.STRING, required = false, defaultValue = "\"lzw\"", enumValues = {"lzw", "deflate", "packbits", "jpeg", "ccitt_g4", "none"}, visibleWhen = "format=tiff",
                    description = "TIFF compression: lzw and deflate are lossless; ccitt_g4 gives the smallest black-and-white files (images are made black and white); jpeg suits photos.") String tiffCompression) {
        List<Object> out = new ArrayList<>();
        String fmt = imageFormat(format);
        String depth = colour == null || colour.isBlank() ? "color" : colour.trim().toLowerCase(Locale.ROOT).replace("grey", "gray").replace("colour", "color");
        if (!List.of("color", "gray", "black_white").contains(depth))
            throw new ActionException("PDF.BAD_COLOR", "Colour must be color, gray or black_white", "Use color, gray or black_white.");
        boolean gray = depth.equals("gray");
        try (PDDocument d = load(ctx, path, password)) {
            Path dir = ctx.resolvePath(folder);
            Files.createDirectories(dir);
            String base = prefix != null && !prefix.isBlank() ? prefix : Path.of(path).getFileName().toString().replaceFirst("(?i)\\.pdf$", "");
            Set<org.apache.pdfbox.cos.COSBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (int pageNo : pages(pages, d.getNumberOfPages())) {
                PDPage p = d.getPage(pageNo - 1);
                ctx.checkCancelled();
                int[] n = {0};
                Map<org.apache.pdfbox.cos.COSBase, double[]> placed = dpi > 0 ? placedSizes(p) : Map.of();
                collectImages(p.getResources(), seen, 0, img -> {
                    java.awt.image.BufferedImage bi = img.getImage();
                    if (bi == null || bi.getWidth() < minSize || bi.getHeight() < minSize) return;
                    double[] size = placed.get(img.getCOSObject());
                    if (size != null) bi = resize(bi, (int) Math.max(1, Math.round(size[0] / 72 * dpi)), (int) Math.max(1, Math.round(size[1] / 72 * dpi)));
                    if (gray) bi = com.oneofficeautomation.packages.ImageFiles.gray(bi);
                    else if (depth.equals("black_white")) bi = com.oneofficeautomation.packages.ImageFiles.binary(bi);
                    File f = dir.resolve(base + "_page" + pageNo + "_img" + (++n[0]) + "." + fmt).toFile();
                    writeImage(bi, fmt, f, fmt.equals("jpg") || fmt.equals("tiff") ? jpegQuality : null, tiffCompression);
                    out.add(f.getPath());
                });
            }
        } catch (IOException e) { throw new ActionException("PDF.IMAGES_FAILED", "Cannot extract images: " + e.getMessage(), "Check the PDF opens in a viewer.");
        }
        return Map.of("files", out, "count", BigDecimal.valueOf(out.size()));
    }

    interface ImageSink { void accept(org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject img) throws IOException; }

    /** Width and height in points at which each image XObject is first drawn on the page (content stream and form XObjects). */
    static Map<org.apache.pdfbox.cos.COSBase, double[]> placedSizes(PDPage page) throws IOException {
        Map<org.apache.pdfbox.cos.COSBase, double[]> sizes = new IdentityHashMap<>();
        new org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine(page) {
            @Override public void drawImage(org.apache.pdfbox.pdmodel.graphics.image.PDImage image) {
                if (!(image instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject x)) return;
                org.apache.pdfbox.util.Matrix m = getGraphicsState().getCurrentTransformationMatrix();
                sizes.putIfAbsent(x.getCOSObject(), new double[]{Math.abs(m.getScalingFactorX()), Math.abs(m.getScalingFactorY())});
            }
            @Override public void appendRectangle(java.awt.geom.Point2D p0, java.awt.geom.Point2D p1, java.awt.geom.Point2D p2, java.awt.geom.Point2D p3) { }
            @Override public void clip(int windingRule) { }
            @Override public void moveTo(float x, float y) { }
            @Override public void lineTo(float x, float y) { }
            @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) { }
            @Override public java.awt.geom.Point2D getCurrentPoint() { return new java.awt.geom.Point2D.Float(); }
            @Override public void closePath() { }
            @Override public void endPath() { }
            @Override public void strokePath() { }
            @Override public void fillPath(int windingRule) { }
            @Override public void fillAndStrokePath(int windingRule) { }
            @Override public void shadingFill(org.apache.pdfbox.cos.COSName shadingName) { }
            void run() throws IOException { processPage(getPage()); }
        }.run();
        return sizes;
    }

    private static java.awt.image.BufferedImage resize(java.awt.image.BufferedImage bi, int w, int h) {
        if (w == bi.getWidth() && h == bi.getHeight()) return bi;
        int type = bi.getColorModel().hasAlpha() ? java.awt.image.BufferedImage.TYPE_INT_ARGB : java.awt.image.BufferedImage.TYPE_INT_RGB;
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(w, h, type);
        var g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(bi, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    /** Visits image XObjects in a page's resources, including those nested in form XObjects; each image is visited once per document. */
    private static void collectImages(org.apache.pdfbox.pdmodel.PDResources res, Set<org.apache.pdfbox.cos.COSBase> seen, int depth, ImageSink sink) throws IOException {
        if (res == null || depth > 10) return;
        for (org.apache.pdfbox.cos.COSName name : res.getXObjectNames()) {
            org.apache.pdfbox.pdmodel.graphics.PDXObject x = res.getXObject(name);
            if (x == null || !seen.add(x.getCOSObject())) continue;
            if (x instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject img) sink.accept(img);
            else if (x instanceof org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject form) collectImages(form.getResources(), seen, depth + 1, sink);
        }
    }

    public Map<String, Object> getProperties(ActionContext ctx, String path, Object password) { return getProperties(ctx, path, password, false); }

    @Action(id = "get_properties", name = "Get document properties", category = "Document", idempotent = true, capabilities = "filesystem",
            description = "Return the title, author, subject, keywords, creator, producer, dates, page count, PDF version and encryption of a PDF.",
            panel = "path, [The file has a password: password] | include_custom")
    @Output(name = "properties", type = OoaType.DICTIONARY,
            description = "title, author, subject, keywords, creator, producer, created, modified, page_count, pdf_version, encrypted, and with include_custom also file_name and custom.")
    public Map<String, Object> getProperties(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "PDF file.") String path,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password for protected PDFs.") Object password,
            @Input(name = "include_custom", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    description = "Also return custom document properties (any other keys in the document information) under custom, plus file_name.") boolean includeCustom) {
        try (PDDocument d = load(ctx, path, password)) {
            org.apache.pdfbox.pdmodel.PDDocumentInformation info = d.getDocumentInformation();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", nz(info.getTitle()));
            m.put("author", nz(info.getAuthor()));
            m.put("subject", nz(info.getSubject()));
            m.put("keywords", nz(info.getKeywords()));
            m.put("creator", nz(info.getCreator()));
            m.put("producer", nz(info.getProducer()));
            m.put("created", date(info.getCreationDate()));
            m.put("modified", date(info.getModificationDate()));
            m.put("page_count", BigDecimal.valueOf(d.getNumberOfPages()));
            m.put("pdf_version", String.valueOf(d.getVersion()));
            m.put("encrypted", d.isEncrypted());
            if (includeCustom) {
                m.put("file_name", Path.of(path).getFileName().toString());
                Set<String> standard = Set.of("Title", "Author", "Subject", "Keywords", "Creator", "Producer", "CreationDate", "ModDate", "Trapped");
                Map<String, Object> custom = new LinkedHashMap<>();
                for (String k : info.getMetadataKeys()) if (!standard.contains(k)) custom.put(k, nz(info.getCustomMetadataValue(k)));
                m.put("custom", custom);
            }
            return Map.of("properties", m);
        } catch (IOException e) { throw new ActionException("PDF.INVALID", e.getMessage()); }
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static java.time.LocalDateTime date(Calendar c) {
        return c == null ? null : java.time.LocalDateTime.ofInstant(c.toInstant(), java.time.ZoneOffset.UTC);
    }

    // ---------- tables from text PDFs, and XPS text (gap G12, 3 Nov 2026) ----------

    /** A word with its box on the page (points from the top-left). */
    record Word(String text, double x0, double x1, double y, double height, double charWidth) { }

    /** The words of one page, as PDFTextStripper finds them. */
    static List<Word> words(PDDocument d, int page) throws IOException {
        List<Word> out = new ArrayList<>();
        PDFTextStripper s = new PDFTextStripper() {
            @Override protected void writeString(String text, List<org.apache.pdfbox.text.TextPosition> pos) {
                if (text.isBlank() || pos.isEmpty()) return;
                org.apache.pdfbox.text.TextPosition a = pos.get(0), b = pos.get(pos.size() - 1);
                double w = 0;
                for (org.apache.pdfbox.text.TextPosition t : pos) w += t.getWidthDirAdj();
                out.add(new Word(text.strip(), a.getXDirAdj(), b.getXDirAdj() + b.getWidthDirAdj(), a.getYDirAdj(),
                        Math.max(1, a.getHeightDir()), Math.max(1, w / pos.size())));
            }
        };
        s.setSortByPosition(true);
        s.setStartPage(page);
        s.setEndPage(page);
        s.getText(d);
        return out;
    }

    /** A cell: neighbouring words on one line that belong together. */
    record Cell(String text, double x0, double x1) { }

    /** Lines of cells, top to bottom: words on the same baseline; separate pieces of text stay separate cells. */
    static List<List<Cell>> lines(List<Word> words, List<Double> lineY) {
        List<Word> sorted = new ArrayList<>(words);
        sorted.sort(Comparator.comparingDouble(Word::y).thenComparingDouble(Word::x0));
        List<List<Word>> rows = new ArrayList<>();
        for (Word w : sorted) {
            List<Word> last = rows.isEmpty() ? null : rows.get(rows.size() - 1);
            if (last != null && Math.abs(last.get(0).y() - w.y()) <= Math.max(2, last.get(0).height() * 0.5)) last.add(w);
            else rows.add(new ArrayList<>(List.of(w)));
        }
        List<List<Cell>> out = new ArrayList<>();
        for (List<Word> r : rows) {
            r.sort(Comparator.comparingDouble(Word::x0));
            List<Cell> cells = new ArrayList<>();
            StringBuilder t = new StringBuilder(r.get(0).text());
            double x0 = r.get(0).x0(), x1 = r.get(0).x1();
            for (int i = 1; i < r.size(); i++) {
                Word w = r.get(i);
                // PDF text already comes in phrases (Blue pens, box of 10); only pieces that nearly touch are joined
                if (w.x0() - x1 > Math.min(w.charWidth(), r.get(i - 1).charWidth()) * 0.6) { cells.add(new Cell(t.toString(), x0, x1)); t = new StringBuilder(w.text()); x0 = w.x0(); }
                else t.append(' ').append(w.text());
                x1 = Math.max(x1, w.x1());
            }
            cells.add(new Cell(t.toString(), x0, x1));
            out.add(cells);
            lineY.add(r.get(0).y());
        }
        return out;
    }

    /** Runs of lines with at least minColumns cells and no big vertical gap, turned into rows of equal width. */
    static List<List<List<String>>> tablesOn(List<List<Cell>> lines, List<Double> ys, int minColumns) {
        List<List<List<String>>> tables = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            if (lines.get(i).size() < minColumns) { i++; continue; }
            int j = i + 1;
            double step = j < lines.size() ? ys.get(j) - ys.get(i) : 0;
            while (j < lines.size() && lines.get(j).size() >= Math.min(2, minColumns)
                    && ys.get(j) - ys.get(j - 1) <= Math.max(step, 1) * 2.5 + 1) j++;
            if (j - i >= 2) tables.add(grid(lines.subList(i, j)));
            i = j;
        }
        return tables;
    }

    /**
     * Columns from the blank strips that (nearly) every row leaves free, so a heading spilling into the next column in
     * one row does not join two columns; each cell goes to the column it overlaps most. A column with only a heading
     * next to one with data but no heading (a left-aligned heading over right-aligned numbers) becomes one column.
     */
    static List<List<String>> grid(List<List<Cell>> rows) {
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (List<Cell> r : rows) for (Cell c : r) { min = Math.min(min, c.x0()); max = Math.max(max, c.x1()); }
        int bins = (int) Math.ceil(max - min) + 2;
        int[] cover = new int[bins];
        for (List<Cell> r : rows) {
            boolean[] row = new boolean[bins];
            for (Cell c : r) for (int b = (int) Math.floor(c.x0() - min); b <= (int) Math.ceil(c.x1() - min) && b < bins; b++) row[Math.max(0, b)] = true;
            for (int b = 0; b < bins; b++) if (row[b]) cover[b]++;
        }
        int blank = rows.size() >= 4 ? rows.size() / 4 : 0;   // a strip covered by this few rows still separates columns
        List<double[]> cols = new ArrayList<>();
        for (int b = 0; b < bins; b++) {
            if (cover[b] <= blank) continue;
            int e = b;
            while (e + 1 < bins && cover[e + 1] > blank) e++;
            cols.add(new double[]{min + b, min + e});
            b = e;
        }
        if (cols.isEmpty()) cols.add(new double[]{min, max});
        List<List<String>> out = new ArrayList<>();
        for (List<Cell> r : rows) {
            String[] row = new String[cols.size()];
            Arrays.fill(row, "");
            for (Cell c : r) {
                int best = 0;
                double bestOverlap = -Double.MAX_VALUE;   // no overlap at all: the nearest column
                for (int k = 0; k < cols.size(); k++) {
                    double ov = Math.min(c.x1(), cols.get(k)[1]) - Math.max(c.x0(), cols.get(k)[0]);
                    if (ov > bestOverlap) { bestOverlap = ov; best = k; }
                }
                row[best] = row[best].isEmpty() ? c.text() : row[best] + " " + c.text();
            }
            out.add(new ArrayList<>(Arrays.asList(row)));
        }
        // join a heading-only column with a neighbouring data column that has no heading
        for (int k = 0; k + 1 < (out.isEmpty() ? 0 : out.get(0).size()); k++) {
            boolean leftHeadOnly = headingOnly(out, k), rightHeadOnly = headingOnly(out, k + 1);
            boolean leftDataOnly = dataOnly(out, k), rightDataOnly = dataOnly(out, k + 1);
            if ((leftHeadOnly && rightDataOnly) || (leftDataOnly && rightHeadOnly)) {
                for (List<String> r : out) {
                    String a = r.get(k), b = r.get(k + 1);
                    r.set(k, a.isEmpty() ? b : b.isEmpty() ? a : a + " " + b);
                    r.remove(k + 1);
                }
                k--;
            }
        }
        return out;
    }

    private static boolean headingOnly(List<List<String>> rows, int col) {
        if (rows.get(0).get(col).isEmpty()) return false;
        for (int i = 1; i < rows.size(); i++) if (!rows.get(i).get(col).isEmpty()) return false;
        return true;
    }

    private static boolean dataOnly(List<List<String>> rows, int col) {
        if (!rows.get(0).get(col).isEmpty()) return false;
        for (int i = 1; i < rows.size(); i++) if (!rows.get(i).get(col).isEmpty()) return true;
        return false;
    }

    private static Map<String, Object> dataTable(List<List<String>> rows, boolean header) {
        int width = rows.stream().mapToInt(List::size).max().orElse(0);
        List<String> columns = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int c = 0; c < width; c++) {
            String n = header && c < rows.get(0).size() && !rows.get(0).get(c).isBlank() ? rows.get(0).get(c).strip() : "Column" + (c + 1);
            String u = n;
            for (int k = 2; !seen.add(u.toLowerCase(Locale.ROOT)); k++) u = n + "_" + k;
            columns.add(u);
        }
        List<Object> data = new ArrayList<>();
        for (List<String> r : header ? rows.subList(1, rows.size()) : rows) {
            List<Object> row = new ArrayList<>(r);
            while (row.size() < width) row.add("");
            data.add(row);
        }
        return Values.table(columns, data);
    }

    @Action(id = "extract_tables", name = "Extract tables", category = "Text", idempotent = true, timeoutSeconds = 300, capabilities = "filesystem",
            description = "Find tables in a PDF that holds text (invoices, statements, reports) by how the text lines up in columns, and return each as a Data Table. Scanned PDFs need OCR first.",
            panel = "path, [The file has a password: password], pages, header, merge_across_pages | min_columns")
    @Output(name = "tables", type = OoaType.LIST, description = "Every table found, as Data Tables, top to bottom and page by page.")
    @Output(name = "table", type = OoaType.TABLE, description = "The first (or only) table; an empty table when none was found.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of tables found.")
    public Map<String, Object> extractTables(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "PDF file.") String path,
            @Input(name = "pages", type = OoaType.STRING, required = false, example = "1,3-4", description = "Pages to read, e.g. 1,3-4; every page when empty.") String pages,
            @Input(name = "header", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "The first row of each table holds the column names; Column1, Column2 … when off.") boolean header,
            @Input(name = "merge_across_pages", type = OoaType.BOOLEAN, required = false, defaultValue = "true",
                    description = "Join a table that continues at the top of the next page (same number of columns) into one, leaving out a repeated header row.") boolean merge,
            @Input(name = "min_columns", type = OoaType.NUMBER, required = false, defaultValue = "2", min = 2, max = 50, description = "Lines need at least this many separate columns to count as a table.") int minColumns,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password for protected PDFs.") Object password) {
        if (minColumns < 2) throw new ActionException("PDF.BAD_OPTION", "min_columns must be at least 2", "A table needs two columns or more.");
        List<List<List<String>>> all = new ArrayList<>();
        int lastPage = -1;
        try (PDDocument d = load(ctx, path, password)) {
            for (int pg : pages(pages, d.getNumberOfPages())) {
                List<Double> ys = new ArrayList<>();
                List<List<List<String>>> found = tablesOn(lines(words(d, pg), ys), ys, minColumns);
                for (int k = 0; k < found.size(); k++) {
                    List<List<String>> t = found.get(k);
                    List<List<String>> prev = all.isEmpty() ? null : all.get(all.size() - 1);
                    if (merge && k == 0 && prev != null && lastPage == pg - 1 && prev.get(0).size() == t.get(0).size()) {
                        boolean repeatedHeader = header && t.get(0).equals(prev.get(0));
                        prev.addAll(repeatedHeader ? t.subList(1, t.size()) : t);
                    } else all.add(new ArrayList<>(t));
                }
                if (!found.isEmpty()) lastPage = pg;
            }
        } catch (IOException e) { throw new ActionException("PDF.TEXT_FAILED", e.getMessage()); }
        List<Object> tables = new ArrayList<>();
        for (List<List<String>> t : all) tables.add(dataTable(t, header));
        Object first = tables.isEmpty() ? Values.table(new ArrayList<>(), new ArrayList<>()) : tables.get(0);
        return Map.of("tables", tables, "table", first, "count", BigDecimal.valueOf(tables.size()));
    }

    @Action(id = "read_xps_text", name = "Read XPS text", category = "Text", idempotent = true, timeoutSeconds = 300, capabilities = "filesystem",
            description = "Read the text of an XPS or OpenXPS document (.xps, .oxps), page by page, in reading order.",
            panel = "path")
    @Output(name = "text", type = OoaType.STRING, description = "The text of every page, pages separated by a blank line.")
    @Output(name = "pages", type = OoaType.LIST, description = "The text of each page.")
    @Output(name = "page_count", type = OoaType.NUMBER, description = "Number of pages.")
    public Map<String, Object> readXpsText(ActionContext ctx, @Input(name = "path", type = OoaType.FILE, description = "XPS file.") String path) {
        Path p = ctx.resolvePath(path);
        if (!Files.isRegularFile(p)) throw new ActionException("FILE.NOT_FOUND", "XPS file not found: " + p, "Check the path.");
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(p.toFile())) {
            List<String> pageNames = new ArrayList<>();
            // page order: the fixed documents' PageContent entries; else every .fpage in name order
            List<String> docs = new ArrayList<>();
            zip.stream().map(java.util.zip.ZipEntry::getName).filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".fdoc")).sorted(xpsOrder()).forEach(docs::add);
            for (String doc : docs) {
                String base = doc.contains("/") ? doc.substring(0, doc.lastIndexOf('/') + 1) : "";
                org.w3c.dom.Document x = xml(zip, doc);
                org.w3c.dom.NodeList pcs = x.getElementsByTagNameNS("*", "PageContent");
                for (int i = 0; i < pcs.getLength(); i++) {
                    String src = ((org.w3c.dom.Element) pcs.item(i)).getAttribute("Source");
                    String name = src.startsWith("/") ? src.substring(1) : java.net.URI.create(base).resolve(src).toString();
                    if (zip.getEntry(name) != null) pageNames.add(name);
                }
            }
            if (pageNames.isEmpty())
                zip.stream().map(java.util.zip.ZipEntry::getName).filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".fpage")).sorted(xpsOrder()).forEach(pageNames::add);
            if (pageNames.isEmpty()) throw new ActionException("PDF.INVALID", p + " has no XPS pages", "Check it is an .xps or .oxps file.");
            List<Object> texts = new ArrayList<>();
            for (String name : pageNames) texts.add(xpsPageText(xml(zip, name)));
            return Map.of("text", String.join("\n\n", texts.stream().map(String::valueOf).toList()), "pages", texts, "page_count", BigDecimal.valueOf(texts.size()));
        } catch (java.util.zip.ZipException e) {
            throw new ActionException("PDF.INVALID", "Cannot read " + p + ": it is not an XPS file", "Check it is an .xps or .oxps file.");
        } catch (IOException e) { throw new ActionException("PDF.TEXT_FAILED", e.getMessage()); }
    }

    /** Names in natural order, so 2.fpage comes before 10.fpage. */
    private static Comparator<String> xpsOrder() {
        return Comparator.comparing((String n) -> n.replaceAll("\\d+", "")).thenComparingLong(n -> {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)(?!.*\\d)").matcher(n);
            return m.find() ? Long.parseLong(m.group(1)) : 0;
        });
    }

    private static org.w3c.dom.Document xml(java.util.zip.ZipFile zip, String name) throws IOException {
        try (java.io.InputStream in = zip.getInputStream(zip.getEntry(name))) {
            javax.xml.parsers.DocumentBuilderFactory f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);   // no external entities
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            return f.newDocumentBuilder().parse(in);
        } catch (javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException e) {
            throw new ActionException("PDF.INVALID", "A page of the XPS file cannot be read: " + e.getMessage(), "Check the file is not damaged.");
        }
    }

    /** The page's Glyphs, as lines by OriginY (top to bottom) and left to right by OriginX within a line. */
    static String xpsPageText(org.w3c.dom.Document page) {
        org.w3c.dom.NodeList gs = page.getElementsByTagNameNS("*", "Glyphs");
        List<double[]> pos = new ArrayList<>();
        List<String> text = new ArrayList<>();
        for (int i = 0; i < gs.getLength(); i++) {
            org.w3c.dom.Element g = (org.w3c.dom.Element) gs.item(i);
            String t = g.getAttribute("UnicodeString");
            if (t.startsWith("{}")) t = t.substring(2);   // XPS escape for text starting with {
            if (t.isBlank()) continue;
            double x = num(g.getAttribute("OriginX")), y = num(g.getAttribute("OriginY")), size = num(g.getAttribute("FontRenderingEmSize"));
            pos.add(new double[]{x, y, Math.max(1, size), text.size()});
            text.add(t);
        }
        pos.sort(Comparator.comparingDouble((double[] a) -> a[1]).thenComparingDouble(a -> a[0]));
        StringBuilder sb = new StringBuilder();
        double lineY = Double.NaN, size = 0;
        for (double[] a : pos) {
            if (!Double.isNaN(lineY) && Math.abs(a[1] - lineY) <= size * 0.5) sb.append(' ');
            else { if (sb.length() > 0) sb.append('\n'); lineY = a[1]; size = a[2]; }
            sb.append(text.get((int) a[3]));
        }
        return sb.toString();
    }

    private static double num(String s) {
        try { return s == null || s.isBlank() ? 0 : Double.parseDouble(s.strip()); } catch (NumberFormatException e) { return 0; }
    }
}
