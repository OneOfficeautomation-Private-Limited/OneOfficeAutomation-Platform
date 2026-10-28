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
                s.setStartPage(Math.max(1, from));
                s.setEndPage(to == null ? d.getNumberOfPages() : Math.min(to, d.getNumberOfPages()));
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
                f.setValue(e.getValue() == null ? "" : Values.text(Values.toJsonable(e.getValue())));
                n++;
            }
            if (!missing.isEmpty()) throw new ActionException("PDF.NO_FIELD", "Fields not found: " + missing, "Use Read form fields to see the exact field names.");
            if (flatten) form.flatten();
            save(ctx, d, target);
            return Map.of("filled", BigDecimal.valueOf(n));
        } catch (IOException e) { throw new ActionException("PDF.FORM_FAILED", e.getMessage()); }
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
    private static void writeImage(java.awt.image.BufferedImage img, String format, File f) throws IOException {
        java.awt.image.BufferedImage out = img;
        if ((format.equals("jpg") || format.equals("bmp")) && img.getType() != java.awt.image.BufferedImage.TYPE_INT_RGB
                && img.getType() != java.awt.image.BufferedImage.TYPE_BYTE_GRAY && img.getType() != java.awt.image.BufferedImage.TYPE_BYTE_BINARY) {
            out = new java.awt.image.BufferedImage(img.getWidth(), img.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
            var g = out.createGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            g.drawImage(img, 0, 0, null);
            g.dispose();
        }
        if (!ImageIO.write(out, format.equals("jpg") ? "jpeg" : format, f)) throw new IOException("No " + format + " writer available");
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

    @Action(id = "to_images", name = "Convert to images", category = "Document", timeoutSeconds = 600, capabilities = "filesystem",
            description = "Save each page, or chosen pages, as an image (PNG, JPEG, BMP, GIF or TIFF) in colour, grey or black and white, for OCR or previews.",
            panel = "path, [The file has a password: password], [Only some pages: pages], format, target_folder, dpi, color | file_prefix")
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
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password for protected PDFs.") Object password) {
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
                writeImage(r.renderImageWithDPI(pg - 1, Math.max(36, Math.min(600, dpi)), type), fmt, f);
                out.add(f.getPath());
            }
        } catch (IOException e) { throw new ActionException("PDF.RENDER_FAILED", e.getMessage()); }
        return Map.of("files", out);
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

    @Action(id = "extract_images", name = "Extract images", category = "Document", timeoutSeconds = 600, capabilities = "filesystem",
            description = "Save the pictures embedded in a PDF (photos, logos, scanned pages), from every page or chosen pages, as image files in a folder.",
            panel = "path, [The file has a password: password], [Only some pages: pages], format, target_folder, file_prefix, color, [Resize to a resolution: dpi] | min_size")
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
            @Input(name = "color", type = OoaType.STRING, required = false, defaultValue = "\"color\"", enumValues = {"color", "gray"}, description = "color keeps the colours; gray saves greyscale copies.") String colour,
            @Input(name = "dpi", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, max = 1200,
                    description = "Resize each image to this many dots per inch at the size it is shown on the page (e.g. a picture shown 2 inches wide becomes 300 pixels wide at 150 dpi); 0 keeps the pixels stored in the PDF. An image the page does not draw keeps its stored size.") int dpi) {
        List<Object> out = new ArrayList<>();
        String fmt = imageFormat(format);
        boolean gray = colour != null && (colour.equalsIgnoreCase("gray") || colour.equalsIgnoreCase("grey"));
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
                    if (gray) {
                        java.awt.image.BufferedImage g = new java.awt.image.BufferedImage(bi.getWidth(), bi.getHeight(), java.awt.image.BufferedImage.TYPE_BYTE_GRAY);
                        var gr = g.createGraphics(); gr.drawImage(bi, 0, 0, null); gr.dispose();
                        bi = g;
                    }
                    File f = dir.resolve(base + "_page" + pageNo + "_img" + (++n[0]) + "." + fmt).toFile();
                    writeImage(bi, fmt, f);
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
}
