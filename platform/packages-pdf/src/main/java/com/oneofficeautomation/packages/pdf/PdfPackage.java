package com.oneofficeautomation.packages.pdf;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.Splitter;
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

    private static void save(ActionContext ctx, PDDocument doc, String target) {
        Path out = ctx.resolvePath(target);
        try {
            if (out.getParent() != null) Files.createDirectories(out.getParent());
            doc.save(out.toFile());
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot save " + out + ": " + e.getMessage(), "Close the file if it is open.", true); }
    }

    @Action(id = "extract_text", name = "Extract text", category = "Text", idempotent = true, timeoutSeconds = 300, capabilities = "filesystem",
            description = "Extract the text of a PDF, or of a page range. Scanned PDFs (images) need OCR instead.")
    @Output(name = "text", type = OoaType.STRING, description = "Extracted text.")
    @Output(name = "page_count", type = OoaType.NUMBER, description = "Total pages in the document.")
    public Map<String, Object> extractText(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "PDF file.") String path,
            @Input(name = "from_page", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "First page (1-based).") int from,
            @Input(name = "to_page", type = OoaType.NUMBER, required = false, description = "Last page; the end when empty.") Integer to,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password for protected PDFs.") Object password) {
        try (PDDocument d = load(ctx, path, password)) {
            PDFTextStripper s = new PDFTextStripper();
            s.setSortByPosition(true);
            s.setStartPage(Math.max(1, from));
            s.setEndPage(to == null ? d.getNumberOfPages() : Math.min(to, d.getNumberOfPages()));
            return Map.of("text", s.getText(d), "page_count", BigDecimal.valueOf(d.getNumberOfPages()));
        } catch (IOException e) { throw new ActionException("PDF.TEXT_FAILED", e.getMessage()); }
    }

    @Action(id = "page_count", name = "Get page count", category = "Document", idempotent = true, capabilities = "filesystem", description = "Return the number of pages.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of pages.")
    public Map<String, Object> pageCount(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "PDF file.") String path,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password for protected PDFs.") Object password) {
        try (PDDocument d = load(ctx, path, password)) { return Map.of("count", BigDecimal.valueOf(d.getNumberOfPages())); }
        catch (IOException e) { throw new ActionException("PDF.INVALID", e.getMessage()); }
    }

    @Action(id = "merge", name = "Merge PDFs", category = "Document", timeoutSeconds = 600, capabilities = "filesystem", description = "Combine several PDFs, in order, into one file.")
    @Output(name = "page_count", type = OoaType.NUMBER, description = "Pages in the merged file.")
    public Map<String, Object> merge(ActionContext ctx,
            @Input(name = "files", type = OoaType.LIST, description = "PDF paths in the order to merge.") List<Object> files,
            @Input(name = "target", type = OoaType.FILE, description = "Merged PDF to create.") String target) {
        if (files.size() < 2) throw new ActionException("PDF.NEED_FILES", "Give at least two PDFs to merge");
        List<PDDocument> sources = new ArrayList<>();
        try (PDDocument out = new PDDocument()) {
            for (Object f : files) {
                PDDocument src = load(ctx, Values.text(f), null);
                sources.add(src);
                for (PDPage page : src.getPages()) out.importPage(page);
            }
            save(ctx, out, target);
            return Map.of("page_count", BigDecimal.valueOf(out.getNumberOfPages()));
        } catch (IOException e) { throw new ActionException("PDF.MERGE_FAILED", e.getMessage());
        } finally { for (PDDocument d : sources) try { d.close(); } catch (IOException ignored) { } }
    }

    @Action(id = "split", name = "Split PDF", category = "Document", timeoutSeconds = 600, capabilities = "filesystem",
            description = "Split a PDF into files of N pages each (1 = one file per page).")
    @Output(name = "files", type = OoaType.LIST, description = "Created files.")
    public Map<String, Object> split(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "PDF file.") String path,
            @Input(name = "target_folder", type = OoaType.FILE, description = "Folder for the parts.") String folder,
            @Input(name = "pages_per_file", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "Pages in each part.") int n) {
        List<Object> created = new ArrayList<>();
        try (PDDocument d = load(ctx, path, null)) {
            Splitter sp = new Splitter();
            sp.setSplitAtPage(Math.max(1, n));
            List<PDDocument> parts = sp.split(d);
            String base = Path.of(path).getFileName().toString().replaceFirst("(?i)\\.pdf$", "");
            for (int i = 0; i < parts.size(); i++) {
                try (PDDocument part = parts.get(i)) {
                    String rel = Path.of(folder, base + "_part" + (i + 1) + ".pdf").toString();
                    save(ctx, part, rel);
                    created.add(ctx.resolvePath(rel).toString());
                }
            }
        } catch (IOException e) { throw new ActionException("PDF.SPLIT_FAILED", e.getMessage()); }
        return Map.of("files", created);
    }

    @Action(id = "read_form", name = "Read form fields", category = "Forms", idempotent = true, capabilities = "filesystem", description = "Read the names and values of all form fields.")
    @Output(name = "fields", type = OoaType.DICTIONARY, description = "Field name to value.")
    public Map<String, Object> readForm(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "PDF form.") String path,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password for protected PDFs.") Object password) {
        try (PDDocument d = load(ctx, path, password)) {
            PDAcroForm form = d.getDocumentCatalog().getAcroForm();
            Map<String, Object> out = new LinkedHashMap<>();
            if (form != null) for (PDField f : form.getFieldTree()) if (f.getFieldType() != null) out.put(f.getFullyQualifiedName(), f.getValueAsString());
            return Map.of("fields", out);
        } catch (IOException e) { throw new ActionException("PDF.INVALID", e.getMessage()); }
    }

    @Action(id = "fill_form", name = "Fill form fields", category = "Forms", capabilities = "filesystem",
            description = "Fill form fields by name and save a new PDF; optionally flatten so values can no longer be edited.")
    @Output(name = "filled", type = OoaType.NUMBER, description = "Number of fields filled.")
    public Map<String, Object> fillForm(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "PDF form.") String path,
            @Input(name = "values", type = OoaType.DICTIONARY, description = "Field name to value.") Map<String, Object> values,
            @Input(name = "target", type = OoaType.FILE, description = "Filled PDF to create.") String target,
            @Input(name = "flatten", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Make the form read-only.") boolean flatten) {
        try (PDDocument d = load(ctx, path, null)) {
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

    @Action(id = "encrypt", name = "Encrypt PDF", category = "Security", capabilities = "filesystem", description = "Protect a PDF with AES-256 passwords.")
    public Map<String, Object> encrypt(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "PDF file.") String path,
            @Input(name = "target", type = OoaType.FILE, description = "Encrypted PDF to create.") String target,
            @Input(name = "user_password", type = OoaType.CREDENTIAL, description = "Password needed to open the PDF.") SecureString user,
            @Input(name = "owner_password", type = OoaType.CREDENTIAL, description = "Password needed to change permissions.") SecureString owner,
            @Input(name = "allow_printing", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Allow printing.") boolean print) {
        try (PDDocument d = load(ctx, path, null)) {
            AccessPermission ap = new AccessPermission();
            ap.setCanPrint(print);
            StandardProtectionPolicy spp = new StandardProtectionPolicy(owner.reveal(), user.reveal(), ap);
            spp.setEncryptionKeyLength(256);
            d.protect(spp);
            save(ctx, d, target);
        } catch (IOException e) { throw new ActionException("PDF.ENCRYPT_FAILED", e.getMessage()); }
        return Map.of();
    }

    @Action(id = "decrypt", name = "Decrypt PDF", category = "Security", capabilities = "filesystem", description = "Remove password protection (needs the password).")
    public Map<String, Object> decrypt(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Protected PDF.") String path,
            @Input(name = "password", type = OoaType.CREDENTIAL, description = "Password from the vault.") SecureString password,
            @Input(name = "target", type = OoaType.FILE, description = "Unprotected PDF to create.") String target) {
        try (PDDocument d = load(ctx, path, password)) {
            d.setAllSecurityToBeRemoved(true);
            save(ctx, d, target);
        } catch (IOException e) { throw new ActionException("PDF.DECRYPT_FAILED", e.getMessage()); }
        return Map.of();
    }

    @Action(id = "to_images", name = "Convert to images", category = "Document", timeoutSeconds = 600, capabilities = "filesystem",
            description = "Save each page as a PNG image (for OCR or previews).")
    @Output(name = "files", type = OoaType.LIST, description = "Created images.")
    public Map<String, Object> toImages(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "PDF file.") String path,
            @Input(name = "target_folder", type = OoaType.FILE, description = "Folder for the images.") String folder,
            @Input(name = "dpi", type = OoaType.NUMBER, required = false, defaultValue = "150", description = "Resolution; 300 for OCR.") int dpi) {
        List<Object> out = new ArrayList<>();
        try (PDDocument d = load(ctx, path, null)) {
            PDFRenderer r = new PDFRenderer(d);
            Path dir = ctx.resolvePath(folder);
            Files.createDirectories(dir);
            String base = Path.of(path).getFileName().toString().replaceFirst("(?i)\\.pdf$", "");
            for (int i = 0; i < d.getNumberOfPages(); i++) {
                ctx.checkCancelled();
                File f = dir.resolve(base + "_page" + (i + 1) + ".png").toFile();
                ImageIO.write(r.renderImageWithDPI(i, Math.max(36, Math.min(600, dpi))), "png", f);
                out.add(f.getPath());
            }
        } catch (IOException e) { throw new ActionException("PDF.RENDER_FAILED", e.getMessage()); }
        return Map.of("files", out);
    }

    @Action(id = "extract_images", name = "Extract images", category = "Document", timeoutSeconds = 600, capabilities = "filesystem",
            description = "Save the pictures embedded in a PDF (photos, logos, scanned pages) as PNG files in a folder.")
    @Output(name = "files", type = OoaType.LIST, description = "Created image files.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of images saved.")
    public Map<String, Object> extractImages(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "PDF file.") String path,
            @Input(name = "target_folder", type = OoaType.FILE, description = "Folder for the images.") String folder,
            @Input(name = "min_size", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "Skip images narrower or lower than this many pixels (e.g. 32 to skip icons).") int minSize,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password for protected PDFs.") Object password) {
        List<Object> out = new ArrayList<>();
        try (PDDocument d = load(ctx, path, password)) {
            Path dir = ctx.resolvePath(folder);
            Files.createDirectories(dir);
            String base = Path.of(path).getFileName().toString().replaceFirst("(?i)\\.pdf$", "");
            Set<org.apache.pdfbox.cos.COSBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            int page = 0;
            for (PDPage p : d.getPages()) {
                page++;
                ctx.checkCancelled();
                int[] n = {0};
                final int pageNo = page;
                collectImages(p.getResources(), seen, 0, img -> {
                    java.awt.image.BufferedImage bi = img.getImage();
                    if (bi == null || bi.getWidth() < minSize || bi.getHeight() < minSize) return;
                    File f = dir.resolve(base + "_page" + pageNo + "_img" + (++n[0]) + ".png").toFile();
                    if (!ImageIO.write(bi, "png", f)) throw new IOException("No PNG writer available");
                    out.add(f.getPath());
                });
            }
        } catch (IOException e) { throw new ActionException("PDF.IMAGES_FAILED", "Cannot extract images: " + e.getMessage(), "Check the PDF opens in a viewer.");
        }
        return Map.of("files", out, "count", BigDecimal.valueOf(out.size()));
    }

    interface ImageSink { void accept(org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject img) throws IOException; }

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

    @Action(id = "get_properties", name = "Get document properties", category = "Document", idempotent = true, capabilities = "filesystem",
            description = "Return the title, author, subject, keywords, creator, producer, dates, page count, PDF version and encryption of a PDF.")
    @Output(name = "properties", type = OoaType.DICTIONARY,
            description = "title, author, subject, keywords, creator, producer, created, modified, page_count, pdf_version, encrypted.")
    public Map<String, Object> getProperties(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "PDF file.") String path,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password for protected PDFs.") Object password) {
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
            return Map.of("properties", m);
        } catch (IOException e) { throw new ActionException("PDF.INVALID", e.getMessage()); }
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static java.time.LocalDateTime date(Calendar c) {
        return c == null ? null : java.time.LocalDateTime.ofInstant(c.toInstant(), java.time.ZoneOffset.UTC);
    }
}
